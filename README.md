# simple-log-analyzer
Personal project with the objective of getting to use Claude Code in a fairly serious fashion (I've had experience with Gemini CLI, Codex and chat-only with Copilot and Claude but never Claude Code),
while learning things and have fun on the side.

The project will implement a mini Datadog-like tool (or at least the little I know of Datadog, never having had the opportunity to test it out), with support
for a few log formats including a custom log format for metric publishing, log filtering, field extraction, log queries using a DSL, graph visualization in near
real time etc. Or at least, if I find enough time to do all of that!

## AI usage

I will aim to meet very high production-level standards in my development process, including design documents and thorough testing. I will allow myself more leeway
for implementation complexity, scalability and performance. It is clear that I cannot, on my own and with the time I want to dedicate to it, deliver something
anywhere near to compete with a serious log management tool. I plan to use Markdown files for my design documents, todo list, feature backlog etc, so that I can
work rigorously alongside the AI and providing it robust context. I will have a design overview in a file, pointing to more specific ADRs (Architecture Design Records),
each one being a Markdown file detailing a single design decision. I might split the design between high-level and low-level design, but I haven't decided yet.

I will strive to find the right balance between using Claude to generate code, and writing code myself. Rule of thumb:
- using AI as a sounding board during the design, perhaps also writing some of the ADRs or at least the first draft
- heavy use of AI to write tests, after writing a robust unit test skill to meet the high testing standards I want to enforce
- fairly heavy use of AI for visual components
- low to medium AI usage in the back-end
- review-only AI usage for algorithms and parts where I want to learn the most
- I will review 100% of the code written by the LLM, except for CSS (the less I have to look at CSS, the happier I am), and probably with lighter attention on tests

## Learning objectives
- test approaches to work with Claude Code on complex topics starting from scratch from design to implementation rather than on smaller tasks building on 
  top of an existing codebase
- have my first project using Ktor, and manipulate Kotlin coroutines more than I have so far
- learn about inverted indexing techniques as well as memory mapping and efficient dictionary storage for range queries (using B or B+ tree)
- use Lucene in a concrete use-case
- gain some experience using Electron to build a cross-platform desktop app
- get some more experience with Kotlin and Angular, both of which I know but haven't practiced a lot

## Targeted features

### High priority
TODO

### Medium priority
TODO

### Low priority
TODO

### Out-of-scope
TODO