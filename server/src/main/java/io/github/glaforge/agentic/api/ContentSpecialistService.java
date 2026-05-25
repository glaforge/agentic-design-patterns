package io.github.glaforge.agentic.api;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.tools.UrlContextTool;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.adk.agents.PlannerAgent;
import com.google.adk.planner.goap.AgentMetadata;
import com.google.adk.planner.goap.GoalOrientedPlanner;

import io.reactivex.rxjava3.core.Flowable;

@ApplicationScoped
public class ContentSpecialistService {

    public record StreamEventDto(String text, String infographicBase64, String infographicMimeType,
            List<String> artifacts, String finalTextResult) {
    }

    public Flowable<Event> runWorkflow(String url, String goal) {
        LlmAgent contentCollector = LlmAgent.builder()
                .name("content_collector")
                .model("gemini-3.1-flash-lite")
                .description("Collects content to work on")
                .instruction("""
                        Your role is to collect content to work on.
                        Reply with the EXACT input you receive, without any additional text or changes.
                        """)
                .outputKey("content")
                .build();

        LlmAgent topicResearcher = LlmAgent.builder()
                .name("topic_researcher")
                .model("gemini-3.1-flash-lite")
                .description("Researches a topic")
                .instruction("""
                        Your role is to research a given topic and provide a well-structured report.
                        You'll use the Google Search tool to find relevant information.
                        """)
                .tools(new GoogleSearchTool())
                .outputKey("report")
                .build();

        LlmAgent summarizer = LlmAgent.builder()
                .name("summarizer")
                .model("gemini-3.1-flash-lite")
                .description("Summarizes content")
                .instruction("""
                        Your role is to summarize content in a concise and accurate way,
                        without omitting any key information.

                        If you're given a URL of an article or a YouTube video,
                        use the URL Context tool to fetch the content and then summarize it.

                        If there's no URL in the prompt, summarize the given text.
                        """)
                .tools(new UrlContextTool())
                .outputKey("summary")
                .build();

        LlmAgent infographicArtist = LlmAgent.builder()
                .name("infographic_artist")
                .model("gemini-3.1-flash-image-preview")
                .description("Creates an infographic from content")
                .instruction("""
                        Your role is to create an infographic from content.
                        """)
                .outputKey("infographic")
                .build();

        LlmAgent ytShortsCreator = LlmAgent.builder()
                .name("yt_shorts_creator")
                .model("gemini-3.1-flash-lite")
                .description("Creates a YouTube Shorts script from a summarized topic")
                .instruction("""
                        As a YouTube Shorts experienced creator, you create engaging and viral scripts.
                        Your role is to create a YouTube Shorts script from the provided content summary.
                        Be factual, catchy, use humor, and keep the viewer engaged.
                        But avoid to be too sycophantic or to use clickbait & superlatives.
                        """)
                .outputKey("yt_shorts_script")
                .build();

        List<AgentMetadata> metadata = List.of(
                new AgentMetadata("content_collector", ImmutableList.of(), "content"),
                new AgentMetadata("topic_researcher", ImmutableList.of(), "report"),
                new AgentMetadata("summarizer", ImmutableList.of("content", "report"), "summary"),
                new AgentMetadata("infographic_artist", ImmutableList.of("summary"), "infographic"),
                new AgentMetadata("yt_shorts_creator", ImmutableList.of("summary", "infographic"), "yt_shorts_script"));

        PlannerAgent agent = PlannerAgent.builder()
                .name("content_pipeline")
                .planner(new GoalOrientedPlanner(goal, metadata))
                .subAgents(contentCollector, topicResearcher, summarizer, infographicArtist, ytShortsCreator)
                .build();

        InMemorySessionService sessionService = new InMemorySessionService();
        Runner runner = InMemoryRunner.builder()
                .sessionService(sessionService)
                .appName("content_specialist_app")
                .agent(agent)
                .build();
        SessionKey sessionKey = new SessionKey("content_specialist_app", "user",
                "session_" + System.currentTimeMillis());
        runner.sessionService().createSession(sessionKey).blockingGet();

        return runner.runAsync(sessionKey, Content.fromParts(Part.fromText(url)));
    }
}
