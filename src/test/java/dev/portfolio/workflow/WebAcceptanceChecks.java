package dev.portfolio.workflow;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static dev.portfolio.store.SnapshotStore.*;

public class WebAcceptanceChecks {
 static JsonNode request(MockMvc mvc,String path,Object body,int expected)throws Exception{
  var json=new ObjectMapper();var result=mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn();
  if(result.getResponse().getStatus()!=expected)throw new AssertionError(path+" returned "+result.getResponse().getStatus()+": "+result.getResponse().getContentAsString());
  return json.readTree(result.getResponse().getContentAsByteArray());
 }
 public static void main(String[] args)throws Exception{
  var directory=Files.createTempDirectory(Files.createDirectories(Path.of("target/test-results")),"mvc-workflow-");
  try(var s=new WorkflowService(directory.toString())){
   var mvc=MockMvcBuilders.standaloneSetup(new Api(s)).build();
   var w=request(mvc,"/api/workflows",map("templateId","standard-v1","guardian",true),200);var id=w.get("ID").asText();var path="/api/workflows/"+id+"/actions";
   request(mvc,path,map("version",0,"role","REVIEWER","payload","bad role","failArchiveOnce",false),403);
   w=request(mvc,path,map("version",0,"role","APPLICANT","payload","first","failArchiveOnce",false),200);
   request(mvc,path,map("version",0,"role","APPLICANT","payload","stale","failArchiveOnce",false),409);
   while(!w.get("CURRENT").isNull())w=request(mvc,path,map("version",w.get("VERSION").asInt(),"role",w.get("CURRENT").get("role").asText(),"payload","test","failArchiveOnce",false),200);
   request(mvc,"/api/worker/tick",map(),200);
   var state=new ObjectMapper().readTree(mvc.perform(get("/api/workflows/"+id)).andReturn().getResponse().getContentAsByteArray());
   var jid=state.get("JOBS").get(0).get("ID").asText();var archive=mvc.perform(get("/api/archives/"+jid)).andReturn().getResponse();
   if(archive.getStatus()!=200||archive.getContentAsByteArray()[0]!='P')throw new AssertionError("archive route");
   request(mvc,"/api/jobs/"+jid+"/retry",map(),409);
   request(mvc,"/api/workflows",map("templateId","missing","guardian",false),404);
  }
  System.out.println("PASS workflow MVC: JSON records, role 403, stale version 409, worker and archive download routes");
 }
}
