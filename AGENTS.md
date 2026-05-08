# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build & Test

```bash
./mvnw compile              # Compile
./mvnw test                 # Run all tests (slow — full Spring context + LLM calls)
./mvnw test -Dtest="GameModelsTest,ThreeKingdomsWerewolfGameTest"  # Unit tests only (fast, no LLM)
./mvnw spring-boot:run      # Run the app (executes all CommandLineRunner demos)
```

Single test class example:
```bash
./mvnw test -Dtest="ReActDemoRunnerTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Tests under `com.axin.kagent.agentscope.game` are pure unit tests (no LLM). All other tests load the full Spring context and make real API calls to the configured LLM.

## Architecture

Java 17, Spring Boot 3.5, Spring AI 1.0 (`spring-ai-starter-model-openai`) pointing to DeepSeek API. Maven wrapper included.

### LLM layer

`LlmClient` (`llm/`) is the single Spring component wrapping `ChatModel` from Spring AI. It converts the domain `Message` record (role + content) into Spring AI message types, streams the response via `Flux<ChatResponse>`, prints tokens in real time, and returns the full text. All agents depend on this one class.

### Paradigm agents (Chapter 4)

Each paradigm lives in its own sub-package under `agent/`:

| Package | Pattern | Key feature |
|---------|---------|-------------|
| `agent.react` | Thought → Action → Observation loop | Uses `ToolExecutor` + `SearchTool` (SerpApi). Regex parses `Thought:`/`Action:` from LLM output. Max steps = 5. |
| `agent.planandsolve` | Plan then execute | `Planner` generates a JSON array plan via LLM, parsed with Jackson. `Executor` loops the plan with history accumulation. |
| `agent.reflection` | Execute → Reflect → Refine | `Memory` stores execution/reflection records. Stops when reflection says "no improvement needed". |

### AgentScope layer (Chapter 6)

`agentscope/` uses `io.agentscope:agentscope-spring-boot-starter:1.0.12`. `WerewolfAgent` extends `AgentBase`, implementing `doCall(List<Msg>)` → `Mono<Msg>` using `LlmClient`. `ThreeKingdomsWerewolfGame` uses `MsgHub` (builder pattern with `enter()`/`exit()`), `Pipelines.fanout()` for parallel voting, and structured output models (`GameModels` records) for LLM output constraints.

### Tool system

`Tool` interface (`getName`, `getDescription`, `execute`). `ToolExecutor` auto-discovers `Tool` beans via Spring injection. `SearchTool` calls SerpApi's HTTP API directly with intelligent answer-box/knowledge-graph parsing.

### Demo runners

Each paradigm has a `@Component` `CommandLineRunner` in `com.axin.kagent`:
- `ReActDemoRunner` (no `@Order`) — searches for NVIDIA's latest GPU
- `PlanAndSolveDemoRunner` (`@Order(2)`) — fruit store math problem
- `ReflectionDemoRunner` (`@Order(3)`) — prime number code generation
- `AgentScopeDemoRunner` (`@Order(4)`, `@ConditionalOnProperty(game.werewolf.enabled=true)`) — Three Kingdoms Werewolf

All runners execute when the Spring context starts. `@SpringBootTest` loads the full context, so tests also trigger them.

### Isolating the Werewolf game

`WerewolfGameRunner.main()` and `com.axin.werewolf.WerewolfGameRunnerTest` use a minimal `@SpringBootApplication` + `@Import(LlmClient.class)` inner config to avoid loading any `CommandLineRunner` beans. The `@ComponentScan` is confined to the runner's own package, so no demo runners are picked up.

## Key conventions

- Domain `Message` (`com.axin.kagent.llm.Message`) is a simple record — all agents use it to pass messages to `LlmClient`. Internal conversion to Spring AI / AgentScope message types happens at the boundary.
- Agent classes are plain Java (no `@Component`) — instantiated manually in demo runners, receiving `LlmClient` via constructor.
- Temperature defaults to 0.0 for deterministic output.
- New paradigm implementations should NOT modify existing code — add new packages and demo runners instead.
