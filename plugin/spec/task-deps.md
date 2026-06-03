# Your task

generate makefile targets for each task with dependencies.

# How to implement

- Use task.getName() as makefile target
- Use task.getDependsOn() as list of dependent task, then cast to Task and use getName()
- add target and dependencies in private void processTask(Task task)
- generateMakefile() should list all collected tasks as dependency of all
