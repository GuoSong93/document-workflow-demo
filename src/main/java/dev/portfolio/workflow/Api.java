package dev.portfolio.workflow;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController
@RequestMapping("/api")
public class Api {
 private final WorkflowService s; public Api(WorkflowService s){this.s=s;}
 @GetMapping("/state") Object state(){return s.overview();}
 @PostMapping("/templates") Object template(@RequestBody WorkflowService.Template t){return s.template(t);}
 record Start(String templateId,boolean guardian,String previousId){}
 @PostMapping("/workflows") Object start(@RequestBody Start r){return s.start(r.templateId(),r.guardian(),r.previousId());}
 @GetMapping("/workflows/{id}") Object get(@PathVariable("id") String id){return s.get(id);}
 record Action(int version,String role,String payload,boolean failArchiveOnce){}
 @PostMapping("/workflows/{id}/actions") Object act(@PathVariable("id") String id,@RequestBody Action r){return s.act(id,r.version(),r.role(),r.payload(),r.failArchiveOnce());}
 @PostMapping("/worker/tick") Object tick(){return s.tick();}
 @PostMapping("/jobs/{id}/retry") Object retry(@PathVariable("id") String id){return s.retry(id);}
 @GetMapping("/archives/{id}") ResponseEntity<byte[]> archive(@PathVariable("id") String id){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=archive.zip").contentType(MediaType.APPLICATION_OCTET_STREAM).body(s.archive(id));}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,NoSuchElementException.class,SecurityException.class})
 ResponseEntity<?> error(RuntimeException e){return ResponseEntity.status(e instanceof SecurityException?403:e instanceof NoSuchElementException?404:e instanceof IllegalStateException?409:400).body(Map.of("error",e.getMessage()));}
}
