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

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.agentic.declarative.K;
import dev.langchain4j.agentic.declarative.TypedKey;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentResponse;
import io.github.glaforge.ansiren.MarkdownRenderer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

@ApplicationScoped
public class ParallelSummarizationService {

    public record EvaluationResult(List<String> summaries, ComparisonResult finalComparison, String bigSummaryOutput, FinalComparisonResult absoluteFinal) {}

    public static class Text implements TypedKey<String> { @Override public String name() { return "text"; } }
    public static class Summary implements TypedKey<String> { @Override public String name() { return "summary"; } }
    public static class Articles implements TypedKey<List<String>> { @Override public String name() { return "articles"; } }
    public static class Summaries implements TypedKey<List<String>> { @Override public String name() { return "summaries"; } }
    public static class Article implements TypedKey<String> { @Override public String name() { return "article"; } }
    public static class Comparison implements TypedKey<ComparisonResult> { @Override public String name() { return "comparison"; } }
    public static class BigSummary implements TypedKey<String> { @Override public String name() { return "big_summary"; } }
    public static class FinalComparison implements TypedKey<FinalComparisonResult> { @Override public String name() { return "final_comparison"; } }

    public interface SummarizerAgent {
        @UserMessage("Provide a comprehensive summary of the following text:\\n{{text}}")
        @Agent(description = "Summarizes the given text", typedOutputKey = Summary.class)
        String summarize(@K(Text.class) String text);
    }

    public interface BatchSummarizerAgent extends AgentInstance {
        @Agent(description = "Generates a batch of summaries", typedOutputKey = Summaries.class)
        List<String> summarizeBatch(@K(Articles.class) List<String> articles);
    }

    public record ComparisonResult(int index, String content, String rationale) {
        @Override
        public String toString() {
            return content;
        }
    }
    public enum Winner { SMALL_MODELS, BIG_MODEL }
    public record FinalComparisonResult(Winner winner, String rationale) {}

    public interface ComparatorAgent {
        @UserMessage("You are an expert editor. Below are 3 summaries of the same article:\\n<summaries>\\n{{summaries}}\\n</summaries>\\n\\nHere is the original article:\\n<article>\\n{{article}}\\n</article>\\n\\nCompare the 3 summaries against the original article.\\nExplain which summary is the most accurate and why.")
        @Agent(description = "Compares the summaries against the original article", typedOutputKey = Comparison.class)
        ComparisonResult compareSummaries(@K(Summaries.class) List<String> summaries, @K(Article.class) String article);
    }

    public interface BigSummarizerAgent {
        @UserMessage("Provide a comprehensive, high-quality summary of the following text:\\n{{article}}")
        @Agent(description = "Generates a high-quality summary using the big model", typedOutputKey = BigSummary.class)
        String summarizeBig(@K(Article.class) String article);
    }

    public interface FinalComparatorAgent {
        @UserMessage("You are an expert editor. Below are two summaries of the same article.\\n\\nSummary A (Best from small models):\\n<summary_a>\\n{{comparison}}\\n</summary_a>\\n\\nSummary B (From big model):\\n<summary_b>\\n{{big_summary}}\\n</summary_b>\\n\\nHere is the original article:\\n<article>\\n{{article}}\\n</article>\\n\\nCompare the two summaries against the original article.\\nExplain which one is the most accurate and why.\\nYou MUST explicitly declare the winner as either SMALL_MODELS or BIG_MODEL.")
        @Agent(description = "Compares the small model winner with big model summary", typedOutputKey = FinalComparison.class)
        FinalComparisonResult compareFinal(@K(Comparison.class) ComparisonResult comparison, @K(BigSummary.class) String bigSummary, @K(Article.class) String article);
    }

    public record ParallelStreamEventDto(String agentName, Object output) {}

    public io.smallrye.mutiny.Multi<ParallelStreamEventDto> runWorkflow(String articleUrl) throws Exception {
        String markdownConverterUrl = "https://markdown.new/";
        String articleMarkdownUrl = markdownConverterUrl + articleUrl;

        var request = HttpRequest.newBuilder(URI.create(articleMarkdownUrl)).build();
        String articleContent = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body();

        GoogleAiGeminiChatModel bigModel = GoogleAiGeminiChatModel.builder()
            .modelName("gemini-3.1-pro-preview")
            .apiKey(System.getenv("GEMINI_API_KEY"))
            .build();

        GoogleAiGeminiChatModel mediumModel = GoogleAiGeminiChatModel.builder()
            .modelName("gemini-3-flash-preview")
            .apiKey(System.getenv("GEMINI_API_KEY"))
            .build();

        GoogleAiGeminiChatModel smallModel = GoogleAiGeminiChatModel.builder()
            .modelName("gemini-3-flash-preview")
            .apiKey(System.getenv("GEMINI_API_KEY"))
            .build();

        SummarizerAgent summarizerAgent = AgenticServices.agentBuilder(SummarizerAgent.class)
            .chatModel(smallModel)
            .build();

        BatchSummarizerAgent batchSummarizer = AgenticServices.parallelMapperBuilder(BatchSummarizerAgent.class)
            .subAgents(summarizerAgent)
            .executor(Executors.newFixedThreadPool(3))
            .build();

        ComparatorAgent comparatorAgent = AgenticServices.agentBuilder(ComparatorAgent.class)
            .chatModel(mediumModel)
            .build();

        BigSummarizerAgent bigSummarizer = AgenticServices.agentBuilder(BigSummarizerAgent.class)
            .chatModel(bigModel)
            .build();

        FinalComparatorAgent finalComparator = AgenticServices.agentBuilder(FinalComparatorAgent.class)
            .chatModel(bigModel)
            .build();

        return io.smallrye.mutiny.Multi.createFrom().emitter(emitter -> {
            AgentListener listener = new AgentListener() {
                @Override
                public void afterAgentInvocation(AgentResponse response) {
                    emitter.emit(new ParallelStreamEventDto(response.agentName(), response.output()));
                }
                @Override
                public boolean inheritedBySubagents() {
                    return true;
                }
            };

            UntypedAgent evaluationWorkflow = AgenticServices.sequenceBuilder()
                .subAgents(batchSummarizer, comparatorAgent, bigSummarizer, finalComparator)
                .listener(listener)
                .build();

            try {
                evaluationWorkflow.invokeWithAgenticScope(Map.of(
                    "articles", List.of(articleContent, articleContent, articleContent),
                    "article", articleContent
                ));
                emitter.complete();
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }
}
