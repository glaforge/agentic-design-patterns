# Agentic Design Patterns

This repository demonstrates various **agentic design patterns** implemented using **[LangChain4j](https://github.com/langchain4j/langchain4j)** and the **ADK (Agent Development Kit)** for Java.

## Agentic Design Patterns

The project highlights three key patterns for building robust and intelligent agents:

1. **Progressive Disclosure**
   *Demonstrated in the Draft Content Agent.*
   Instead of overwhelming an agent with all available tools and instructions upfront, skills and capabilities are dynamically disclosed as the agent progresses through its task. This ensures the model stays focused and contextually relevant.

2. **Goal Oriented Action Planning (GOAP)**
   *Demonstrated in the Content Specialist.*
   The agent is given a high-level goal and dynamically constructs an execution plan by selecting the right sub-agents and tools required to reach that specific goal.

3. **LLM-as-Judge**
   *Demonstrated in the Parallel Summaries Agent.*
   Multiple smaller, faster LLMs run in parallel to generate candidate summaries. A larger, more capable LLM then acts as a judge to evaluate and select the best output based on accuracy and conciseness.

## Project Structure

This project is split into two distinct flavors:

- [`agents/`](./agents/)
  Contains standalone, command-line implementations of the agents. This is a great place to start exploring the code and testing the patterns directly from your terminal.

- [`server/`](./server/)
  Contains a complete web application built with **Quarkus**. It wraps the agentic patterns behind REST/SSE endpoints and provides a beautiful, reactive front-end interface to interact with the agents in real-time.

## Disclaimer

This is not an official Google product.

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.
