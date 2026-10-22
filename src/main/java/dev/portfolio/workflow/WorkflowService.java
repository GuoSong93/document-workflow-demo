package dev.portfolio.workflow;
import java.util.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import dev.portfolio.store.SnapshotStore;
import static dev.portfolio.store.SnapshotStore.*;

@Service
public class WorkflowService implements AutoCloseable {
 private final SnapshotStore store;
 public record Stage(String document,String role,String kind,boolean guardianOnly){}
 public record Template(String id,List<Stage> stages){}
 public WorkflowService(@Value("${demo.data-dir:./data}")String path){
  store=new SnapshotStore(Path.of(path));
  store.write(s->{for(var j:rows(s,"jobs"))if(j.get("STATE").equals("RUNNING"))j.put("STATE",number(j,"ATTEMPTS")>=3?"DEAD":"PENDING");return null;});
  template(new Template("standard-v1",List.of(new Stage("A","APPLICANT","FORM",false),new Stage("A","REVIEWER","APPROVE",false),new Stage("A","GUARDIAN","APPROVE",true),new Stage("B","APPLICANT","FORM",false),new Stage("B","REVIEWER","APPROVE",false))));
 }
 private static int number(Map<String,Object> r,String k){return ((Number)r.get(k)).intValue();}
 public Object template(Template t){
  if(t.id()==null||!t.id().matches("[A-Za-z0-9_-]{1,64}")||t.stages()==null||t.stages().isEmpty()||t.stages().size()>12)throw new IllegalArgumentException("template id and 1-12 stages required");
  String current=null;var closed=new HashSet<String>();var plan=new ArrayList<Map<String,Object>>();
  for(var stage:t.stages()){
   if(stage==null||stage.document()==null||!stage.document().matches("[A-Za-z0-9_-]{1,32}")||stage.role()==null||!Set.of("APPLICANT","REVIEWER","GUARDIAN").contains(stage.role())||stage.kind()==null||!Set.of("FORM","APPROVE").contains(stage.kind()))throw new IllegalArgumentException("invalid stage");
   if(!stage.document().equals(current)){if(closed.contains(stage.document()))throw new IllegalArgumentException("document stages must be contiguous");if(current!=null)closed.add(current);current=stage.document();}
   plan.add(map("document",stage.document(),"role",stage.role(),"kind",stage.kind(),"guardianOnly",stage.guardianOnly()));
  }
  return store.write(s->{
   for(var old:rows(s,"templates"))if(old.get("ID").equals(t.id())){if(!old.get("STAGES").equals(plan))throw new IllegalStateException("template immutable; use new id");return old;}
   var item=map("ID",t.id(),"STAGES",plan);rows(s,"templates").add(item);return item;
  });
 }
 @SuppressWarnings("unchecked") private static List<Map<String,Object>> plan(Map<String,Object> r){return (List<Map<String,Object>>)r.get("STAGES");}
 public Object start(String templateId,boolean guardian,String previousId){return store.write(s->{
  var t=require(rows(s,"templates"),"ID",templateId);int round=1;
  if(previousId!=null){
   var previous=require(rows(s,"workflows"),"ID",previousId);
   if(!previous.get("STATUS").equals("COMPLETED"))throw new IllegalStateException("previous round still active");
   for(var w:rows(s,"workflows"))if(previousId.equals(w.get("PREVIOUS_ID")))throw new IllegalStateException("next round already exists");
   round=number(previous,"ROUND_NO")+1;
  }
  var selected=plan(t).stream().filter(st->guardian||!Boolean.TRUE.equals(st.get("guardianOnly"))).toList();
  if(selected.isEmpty())throw new IllegalArgumentException("condition removes all stages");
  String id=UUID.randomUUID().toString();
  rows(s,"workflows").add(map("ID",id,"TEMPLATE_ID",templateId,"ROUND_NO",round,"PREVIOUS_ID",previousId,"GUARDIAN",guardian,"STAGES",selected,"STEP_INDEX",0,"VERSION",0,"STATUS","ACTIVE"));
  return get(s,id);
 });}
 public Object act(String id,int version,String role,String payload,boolean failArchiveOnce){
  if(payload==null||payload.isBlank()||payload.length()>10000)throw new IllegalArgumentException("payload required, max 10000 chars");
  return store.write(s->{
   var w=require(rows(s,"workflows"),"ID",id);
   if(!w.get("STATUS").equals("ACTIVE")||number(w,"VERSION")!=version)throw new IllegalStateException("stale version or closed workflow");
   int index=number(w,"STEP_INDEX");var stages=plan(w);var current=stages.get(index);
   if(!current.get("role").equals(role))throw new SecurityException("role does not own current step");
   rows(s,"actions").add(map("WORKFLOW_ID",id,"STEP_INDEX",index,"ACTOR",role,"PAYLOAD",payload,"AT",Instant.now().toString()));
   int next=index+1;
   if(next==stages.size()||!stages.get(next).get("document").equals(current.get("document")))rows(s,"jobs").add(map("ID",UUID.randomUUID().toString(),"WORKFLOW_ID",id,"DOCUMENT_NAME",current.get("document"),"STATE","PENDING","ATTEMPTS",0,"FAIL_ONCE",failArchiveOnce,"LAST_ERROR",null));
   w.put("STEP_INDEX",next);w.put("VERSION",version+1);w.put("STATUS",next==stages.size()?"COMPLETED":"ACTIVE");
   return get(s,id);
  });
 }
 // Manual bounded worker; claims commit before generation. Restart reclaims RUNNING tasks.
 public synchronized Object tick(){
  List<String> ids=store.read(s->rows(s,"jobs").stream().filter(j->j.get("STATE").equals("PENDING")&&number(j,"ATTEMPTS")<3).map(j->j.get("ID").toString()).toList());
  for(String id:ids){
   var job=store.write(s->{var j=require(rows(s,"jobs"),"ID",id);j.put("STATE","RUNNING");j.put("ATTEMPTS",number(j,"ATTEMPTS")+1);return new LinkedHashMap<>(j);});
   try{
    if(Boolean.TRUE.equals(job.get("FAIL_ONCE"))&&number(job,"ATTEMPTS")==1)throw new IOException("injected archive failure");
    Map<String,Object> packet=store.read(s->{
     var w=require(rows(s,"workflows"),"ID",job.get("WORKFLOW_ID").toString());var stages=plan(w);
     var actions=rows(s,"actions").stream().filter(a->a.get("WORKFLOW_ID").equals(w.get("ID"))&&stages.get(number(a,"STEP_INDEX")).get("document").equals(job.get("DOCUMENT_NAME"))).toList();
     return map("workflowId",w.get("ID"),"round",w.get("ROUND_NO"),"template",w.get("TEMPLATE_ID"),"document",job.get("DOCUMENT_NAME"),"actions",actions,"notice","Demo acknowledgements only, no identity verification or digital signature");
    });
    var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
     zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(packet));zip.closeEntry();
    }
    String content=Base64.getEncoder().encodeToString(bytes.toByteArray());
    store.write(s->{var j=require(rows(s,"jobs"),"ID",id);j.put("STATE","DONE");j.put("LAST_ERROR",null);j.put("ARCHIVE",content);return null;});
   }catch(Exception e){store.write(s->{var j=require(rows(s,"jobs"),"ID",id);j.put("STATE",number(j,"ATTEMPTS")>=3?"DEAD":"PENDING");j.put("LAST_ERROR",e.getMessage());return null;});}
  }
  return overview();
 }
 public Object retry(String id){return store.write(s->{var j=require(rows(s,"jobs"),"ID",id);if(!j.get("STATE").equals("DEAD"))throw new IllegalStateException("only DEAD jobs can be retried");j.put("STATE","PENDING");j.put("ATTEMPTS",0);j.put("FAIL_ONCE",false);j.put("LAST_ERROR",null);return publicOverview(s);});}
 private Map<String,Object> publicJob(Map<String,Object>j){var copy=new LinkedHashMap<>(j);copy.remove("ARCHIVE");return copy;}
 private Object get(Map<String,Object>s,String id){
  var w=require(rows(s,"workflows"),"ID",id);var output=new LinkedHashMap<>(w);int index=number(w,"STEP_INDEX");var plan=plan(w);
  output.put("CURRENT",index<plan.size()?plan.get(index):null);
  output.put("ACTIONS",rows(s,"actions").stream().filter(a->a.get("WORKFLOW_ID").equals(id)).toList());
  output.put("JOBS",rows(s,"jobs").stream().filter(j->j.get("WORKFLOW_ID").equals(id)).map(this::publicJob).toList());return output;
 }
 public Object get(String id){return store.read(s->get(s,id));}
 public byte[] archive(String id){return store.read(s->{var j=require(rows(s,"jobs"),"ID",id);if(!j.get("STATE").equals("DONE"))throw new NoSuchElementException("archive not ready");return Base64.getDecoder().decode(j.get("ARCHIVE").toString());});}
 private Object publicOverview(Map<String,Object>s){return map("workflows",rows(s,"workflows"),"jobs",rows(s,"jobs").stream().map(this::publicJob).toList());}
 public Object overview(){return store.read(this::publicOverview);}
 @PreDestroy @Override public void close(){store.close();}
}
