package dev.portfolio.workflow;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AcceptanceChecks {
 @SuppressWarnings("unchecked") static Map<String,Object> obj(Object x){return (Map<String,Object>)x;}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object x){return (List<Map<String,Object>>)x;}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void fails(Runnable fn){try{fn.run();}catch(RuntimeException expected){return;}throw new AssertionError("expected rejection");}
 static int number(Map<String,Object>r,String k){return ((Number)r.get(k)).intValue();}
 static Object next(WorkflowService service,String id,boolean fail){var w=obj(service.get(id));return service.act(id,number(w,"VERSION"),obj(w.get("CURRENT")).get("role").toString(),"synthetic acknowledgement",fail);}
 public static void main(String[] args)throws Exception{
  var directory=Files.createTempDirectory(Files.createDirectories(Path.of("target/test-results")),"workflow-check-");String id;
  try(var service=new WorkflowService(directory.toString())){
   var w=obj(service.start("standard-v1",false,null));id=w.get("ID").toString();check(list(w.get("STAGES")).size()==4,"conditional guardian skipped");
   var guarded=obj(service.start("standard-v1",true,null));check(list(guarded.get("STAGES")).size()==5,"guardian included");
   fails(()->service.act(id,0,"REVIEWER","wrong role",false));check(number(obj(service.get(id)),"VERSION")==0,"bad role did not advance");
   try(var pool=Executors.newFixedThreadPool(8)){
    var jobs=new ArrayList<Callable<Boolean>>();for(int i=0;i<8;i++)jobs.add(()->{try{service.act(id,0,"APPLICANT","concurrent",false);return true;}catch(IllegalStateException expected){return false;}});
    int passed=0;for(var f:pool.invokeAll(jobs))if(f.get())passed++;check(passed==1,"only one concurrent submission advances");
   }
   fails(()->service.act(id,0,"APPLICANT","stale page",false));
   fails(()->service.start("standard-v1",false,id));
   next(service,id,true); // file A ends; job enters same snapshot as action
   check(list(obj(service.get(id)).get("JOBS")).size()==1,"document completion atomically creates pending job");
   check(obj(obj(service.get(id)).get("CURRENT")).get("document").equals("B"),"file B starts after file A business actions");
   service.tick();var first=list(obj(service.get(id)).get("JOBS")).getFirst();check(first.get("STATE").equals("PENDING")&&number(first,"ATTEMPTS")==1,"first injected failure preserves task");
  }
  try(var service=new WorkflowService(directory.toString())){
   service.tick();var w=obj(service.get(id));var job=list(w.get("JOBS")).getFirst();check(job.get("STATE").equals("DONE"),"retry survives restart");
   var archive=service.archive(job.get("ID").toString());try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){check(zip.getNextEntry().getName().equals("manifest.json"),"readable archive manifest");var packet=new ObjectMapper().readTree(zip.readAllBytes());check(packet.get("actions").size()==2,"archive contains only document A actions");}
   service.tick();check(number(list(obj(service.get(id)).get("JOBS")).getFirst(),"ATTEMPTS")==2,"DONE job not executed twice");
   next(service,id,false);next(service,id,false);check(obj(service.get(id)).get("STATUS").equals("COMPLETED"),"business workflow completed");
   service.tick();check(list(obj(service.get(id)).get("JOBS")).stream().allMatch(j->j.get("STATE").equals("DONE")),"both archives ready");
   var second=obj(service.start("standard-v1",false,id));check(number(second,"ROUND_NO")==2,"new round retains predecessor");fails(()->service.start("standard-v1",false,id));
   fails(()->service.template(new WorkflowService.Template("standard-v1",List.of(new WorkflowService.Stage("C","APPLICANT","FORM",false)))));
   check(list(obj(service.get(id)).get("STAGES")).size()==4,"old instance plan unaffected");
  }
  // Persist a realistic crash boundary: worker claim committed, archive not generated.
  Path state=directory.resolve("state.json");var json=new ObjectMapper();var data=json.readValue(Files.readAllBytes(state),Map.class);
  var jobs=list(data.get("jobs"));jobs.getFirst().put("STATE","RUNNING");jobs.getFirst().put("ATTEMPTS",1);jobs.getFirst().remove("ARCHIVE");json.writeValue(state.toFile(),data);
  try(var service=new WorkflowService(directory.toString())){service.tick();check(list(obj(service.get(id)).get("JOBS")).getFirst().get("STATE").equals("DONE"),"RUNNING job reclaimed on startup");}
  // A task exhausting its attempt budget enters DEAD and supports explicit retry.
  data=json.readValue(Files.readAllBytes(state),Map.class);jobs=list(data.get("jobs"));jobs.getFirst().put("STATE","RUNNING");jobs.getFirst().put("ATTEMPTS",3);String jid=jobs.getFirst().get("ID").toString();json.writeValue(state.toFile(),data);
  try(var service=new WorkflowService(directory.toString())){
   check(list(obj(service.get(id)).get("JOBS")).getFirst().get("STATE").equals("DEAD"),"exhausted crash task dead letters");service.retry(jid);service.tick();check(list(obj(service.get(id)).get("JOBS")).getFirst().get("STATE").equals("DONE"),"explicit dead-letter retry");
  }
  System.out.println("PASS workflow: conditional plan, role guard, stale version, concurrent submissions, atomic job creation, failure retry, restart recovery, archive isolation, new rounds, immutable template, dead letter retry");
 }
}
