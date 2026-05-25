package io.github.glaforge.agentic;

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
import static io.github.glaforge.ansiren.Ansi.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class ParallelSummarizationEvaluation {

    public static class Text implements TypedKey<String> { @Override public String name() { return "text"; } }
    public static class Summary implements TypedKey<String> { @Override public String name() { return "summary"; } }
    public static class Articles implements TypedKey<List<String>> { @Override public String name() { return "articles"; } }
    public static class Summaries implements TypedKey<List<String>> { @Override public String name() { return "summaries"; } }
    public static class Article implements TypedKey<String> { @Override public String name() { return "article"; } }
    public static class Comparison implements TypedKey<ComparisonResult> { @Override public String name() { return "comparison"; } }
    public static class BigSummary implements TypedKey<String> { @Override public String name() { return "big_summary"; } }
    public static class FinalComparison implements TypedKey<FinalComparisonResult> { @Override public String name() { return "final_comparison"; } }

    public interface SummarizerAgent {
        @UserMessage("""
            Provide a comprehensive summary of the following text:
            {{text}}
            """)
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
        @UserMessage("""
            You are an expert editor. Below are 3 summaries of the same article:
            <summaries>
            {{summaries}}
            </summaries>

            Here is the original article:
            <article>
            {{article}}
            </article>

            Compare the 3 summaries against the original article.
            Explain which summary is the most accurate and why.
            """)
        @Agent(description = "Compares the summaries against the original article", typedOutputKey = Comparison.class)
        ComparisonResult compareSummaries(@K(Summaries.class) List<String> summaries, @K(Article.class) String article);
    }

    public interface BigSummarizerAgent {
        @UserMessage("""
            Provide a comprehensive, high-quality summary of the following text:
            {{article}}
            """)
        @Agent(description = "Generates a high-quality summary using the big model", typedOutputKey = BigSummary.class)
        String summarizeBig(@K(Article.class) String article);
    }

    public interface FinalComparatorAgent {
        @UserMessage("""
            You are an expert editor. Below are two summaries of the same article.

            Summary A (Best from small models):
            <summary_a>
            {{comparison}}
            </summary_a>

            Summary B (From big model):
            <summary_b>
            {{big_summary}}
            </summary_b>

            Here is the original article:
            <article>
            {{article}}
            </article>

            Compare the two summaries against the original article.
            Explain which one is the most accurate and why.
            You MUST explicitly declare the winner as either SMALL_MODELS or BIG_MODEL.
            """)
        @Agent(description = "Compares the small model winner with big model summary", typedOutputKey = FinalComparison.class)
        FinalComparisonResult compareFinal(@K(Comparison.class) ComparisonResult comparison, @K(BigSummary.class) String bigSummary, @K(Article.class) String article);
    }


    public static void main(String[] args) throws Exception {
        var md = new MarkdownRenderer();

        String markdownConverterUrl = "https://markdown.new/";
        String articleUrl = "https://www.quantamagazine.org/the-ai-revolution-in-math-has-arrived-20260413/";
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
            .modelName("gemini-3.1-flash-lite")
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

        UntypedAgent evaluationWorkflow = AgenticServices.sequenceBuilder()
            .subAgents(batchSummarizer, comparatorAgent, bigSummarizer, finalComparator)
            .listener(new AgentListener() {
                @Override
                public void afterAgentInvocation(AgentResponse response) {
                    System.out.println(yellow("\n=== [Intermediary Step Completed: " + response.agentName() + "] ==="));
                    if (response.output() != null) {
                        String out = response.output().toString();
                        System.out.println(md.render(out));
                    }
                    System.out.println("\n---------------------------------------------------");
                }
                @Override
                public boolean inheritedBySubagents() {
                    return true; // Allows catching all parallel items natively
                }
            })
            .build();

        System.out.println(green("Running sequential evaluation workflow..."));
        List<String> articlesToSummarize = List.of(articleContent, articleContent, articleContent);

        var result = evaluationWorkflow.invokeWithAgenticScope(Map.of(
            "articles", articlesToSummarize,
            "article", articleContent
        ));

        List<String> summaries = result.agenticScope().readState(Summaries.class);
        ComparisonResult finalComparison = result.agenticScope().readState(Comparison.class);

        System.out.println("Computed " + summaries.size() + " summaries.");
        for (int i = 0; i < summaries.size(); i++) {
            System.out.println("\n\n" + blue("--- Summary #" + (i + 1) + " -----------") + "\n\n" + md.render(summaries.get(i)));
        }


        // ============================================================================
        // Now: Comparison with a summary generated with a big but more expensive model
        // ============================================================================

        System.out.println(green("\n=== Final Comparison ==========="));

        System.out.println(blue("- Preferred Summary Index:  ") + finalComparison.index());
        System.out.println(blue("- Rationale:\n\n") + md.render(finalComparison.rationale()));
        System.out.println(blue("- Content:\n\n") + md.render(finalComparison.content()));

        String bigSummaryOutput = result.agenticScope().readState(BigSummary.class);
        FinalComparisonResult absoluteFinal = result.agenticScope().readState(FinalComparison.class);


        System.out.println(green("\n\n\n=== Big Model Summary ==========="));

        System.out.println("\n" + md.render(bigSummaryOutput));

        System.out.println("\n" + green("=== ULTIMATE Final Comparison ==========="));
        System.out.println("\n" + yellow("- Winner:  ") + absoluteFinal.winner());
        System.out.println("\n" + yellow("- Rationale:\n\n") + md.render(absoluteFinal.rationale()));
    }
}
