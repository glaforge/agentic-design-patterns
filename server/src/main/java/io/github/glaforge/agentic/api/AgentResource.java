/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.glaforge.agentic.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import com.google.genai.types.Blob;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.reactivex.rxjava3.disposables.Disposable;
import com.google.genai.types.Part;

@Path("/api")
public class AgentResource {

    @Inject
    DraftService draftService;

    @Inject
    ParallelSummarizationService parallelSummarizationService;

    @Inject
    ContentSpecialistService contentSpecialistService;

    public record TopicRequest(String topic) {}
    public record UrlRequest(String articleUrl) {}

    @POST
    @Path("/draft")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DraftService.DraftResult runDraft(TopicRequest request) {
        return draftService.runWorkflow(request.topic());
    }

    @GET
    @Path("/summarize/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Blocking
    public Multi<ParallelSummarizationService.ParallelStreamEventDto> streamSummarize(@QueryParam("url") String url) throws Exception {
        return parallelSummarizationService.runWorkflow(url)
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/specialist/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Blocking
    public Multi<ContentSpecialistService.StreamEventDto> streamSpecialist(@QueryParam("url") String url, @QueryParam("goal") String goal) {
        Multi<ContentSpecialistService.StreamEventDto> stream = Multi.createFrom().emitter(emitter -> {
            Disposable d = contentSpecialistService.runWorkflow(url, goal)
                .subscribe(
                    event -> {
                        String text = "";
                        String infographicBase64 = null;
                        String infographicMimeType = null;
                        List<String> artifacts = new ArrayList<>();

                        if (event.content().isPresent()) {
                            text = event.content().get().text();
                            if (event.content().get().parts().isPresent()) {
                                for (Part part : event.content().get().parts().get()) {
                                    if (part.inlineData().isPresent()) {
                                        Blob blob = part.inlineData().get();
                                        if (blob.data().isPresent()) {
                                            byte[] data = blob.data().get();
                                            infographicMimeType = blob.mimeType().orElse("image/png");
                                            infographicBase64 = Base64.getEncoder().encodeToString(data);
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        String finalTextResult = null;
                        if (event.actions().stateDelta() != null) {
                            for (Map.Entry<String, Object> entry : event.actions().stateDelta().entrySet()) {
                                if (goal.equals(entry.getKey()) && entry.getValue() instanceof String) {
                                    finalTextResult = (String) entry.getValue();
                                }
                            }
                        }
                        
                        if (event.actions().artifactDelta() != null) {
                            for (var entry : event.actions().artifactDelta().entrySet()) {
                                artifacts.add(entry.getKey());
                            }
                        }

                        emitter.emit(new ContentSpecialistService.StreamEventDto(text, infographicBase64, infographicMimeType, artifacts, finalTextResult));
                    },
                    error -> emitter.fail(error),
                    () -> emitter.complete()
                );
            
            emitter.onTermination(() -> d.dispose());
        });
        return stream.runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
