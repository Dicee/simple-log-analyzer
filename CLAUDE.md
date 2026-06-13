
# Documentation

Hi Claude, please find the design documents for this project under the `doc/design` folder. Below are the main files you can use as entry points:
- The [high-level design document](doc/design/high-level-design.md) contains an overview of the project: goals, requirements and high-level architecture. It will
  point to numerous more detailed ADRs (Architecture Design Records) which will go in further details about a specific design decision.
- The `doc/research` folder contains additional notes that we may use during implementation, shouldn't be read in general unless I ask it.
- The [backlog](doc/backlog.md) contains a list of remaining tasks. We will update it as the project goes.

Important rules to follow:
- do not edit document under the `doc` folder nor write a new one without being asked to do so (you can however offer to do it)
- do not unnecessarily read documents that aren't relevant to your current task

## Writing Kotlin tests

Even though it's primarily aimed at Java, please read the `java-gradle-unit-tests-crafting-workflow` skill before writing or executing tests. Most of its guidelines also apply to Kotlin. 
Read the `.devenv` file to understand which environment you are executing under. In Windows, DO NOT TRY USING BASH, duh! If the file is missing, determine the environment by yourself or 
ask the user.

# Architecture review guidelines

From time to time, I will ask you to review an ADR I wrote, or sections from the high-level design. I expect you to act as an experienced engineer with well-balanced priorities. You should be able to understand trade-offs, and that not everything needs to be perfect as long as it's functional, sturdy, and meets the requirements effectively (i.e. cost-efficient to implement and maintain, cognitive load etc).

For each design decision, you should ask the following:
- is the design robust? Are all the edge cases and failure scenarios handled? Can the system recover well in case of a failure?
- is the design minimal enough, oversimplistic or too complicated? Could an alternative design present potentially better trade-offs?
- are technology choices relevant? Could another technology fit the needs better? It should be more than a matter of taste, and bring actual value for you to propose it.
- are there performance bottlenecks in the design? Are there alternatives or variations of this design that could address these bottlenecks? 

To avoid overwhelming the human developer getting your architecture advice, provide your feedback in a structured manner, ideally following the structure of the reviewed document (e.g. "change this section by doing X"). If there are important issues with the design start by highlighting the more critical ones and reserve more detailed comments for later.

Avoid stylistic comments in general unless a sentence is really confusing, mostly focus on the content.