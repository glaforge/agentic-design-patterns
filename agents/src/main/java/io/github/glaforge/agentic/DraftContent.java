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

package io.github.glaforge.agentic;

import java.util.Map;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.Skills;
import io.github.glaforge.ansiren.MarkdownRenderer;
import static io.github.glaforge.ansiren.Ansi.*;

public class DraftContent {

    public interface DraftAgent {
        @Agent(name = "draft_agent",
                description = "Expert in drafting content.")
        @UserMessage("""
            Draft content about: {{topic}}
            Use the `google_search` tool to find relevant information about the requested topic.
            Write in a super exciting and engaging style, with lots of superlatives and hype!
            """)
        String draft(@V("topic") String topic);
    }

    public interface RefinerAgent {
        @Agent(name = "refiner_agent",
                description = "Expert in refining content.")
        @UserMessage("""
            Deslopify this content:
            {{draft}}

            You have access to the `deslopify` skill.
            When the user's request relates to one of these skills, activate it first.
            """)
        String refine(@V("draft") String draft);
    }

    public static void main(String[] args) {
        var md = new MarkdownRenderer();

        FileSystemSkill skill = ClassPathSkillLoader.loadSkill("skills/deslopify");
        Skills skills = Skills.from(skill);

        var modelBuilder = GoogleAiGeminiChatModel.builder()
            .modelName("gemini-3.1-flash-lite-preview")
            .apiKey(System.getenv("GEMINI_API_KEY"))
            //.logRequestsAndResponses(true)
            .sendThinking(true)
            .returnThinking(true);

        var model = modelBuilder.build();

        var modelWithSearch = modelBuilder
            .allowGoogleSearch(true)
            .build();

        AgentListener listener = new AgentListener() {
            @Override
            public void beforeAgentInvocation(AgentRequest agentRequest) {
                System.out.println(blue("BEFORE ") + agentRequest.agentName() + "\n");
            }
            @Override
            public void afterAgentInvocation(AgentResponse agentResponse) {
                System.out.println(green("AFTER ") + agentResponse.agentName() + "\n");
            }
        };

        var draftAgent = AgenticServices.agentBuilder(DraftAgent.class)
            .chatModel(modelWithSearch)
            .listener(listener)
            .outputKey("draft")
            .build();

        var refinerAgent = AgenticServices.agentBuilder(RefinerAgent.class)
            .chatModel(model)
            .toolProviders(skills.toolProvider())
            .listener(listener)
            .outputKey("refined")
            .build();

       var contentWriter = AgenticServices.sequenceBuilder()
            .subAgents(draftAgent, refinerAgent)
            .listener(listener)
            .outputKey("refined")
            .build();

        var result = contentWriter.invokeWithAgenticScope(Map.of(
            "topic", "Latest news in AI from March and April 2026"
        ));

        System.out.println("""
                ---- DRAFT -------------------

                %s

                ---- REFINED -----------------

                %s

                ------------------------------
                """.formatted(
                    md.render(result.agenticScope().readState("draft").toString()),
                    md.render(result.agenticScope().readState("refined").toString()))
                );
    }
}
