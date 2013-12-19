.compute.task.dependencies <- function(task,envir) {
  tname <- getName(task)
  task.names <- get("task.names",envir)
  all.tasks <- get("all.tasks",envir)
  
  if (!is.element(tname,task.names)) {
    task.names <- c(task.names,tname)
    all.tasks <- c(all.tasks, task)
    assign("task.names",task.names,envir)
    assign("all.tasks",all.tasks,envir)
    deps <- getDependencies(task)
    if (length(deps) > 0) {
      for (ii in 1:length(deps)) {
        .compute.task.dependencies(deps[[ii]],envir)
      }
    }
  }
}

PASolve <- function(..., client = PAClient(), .debug = PADebug(), jobName = str_c("PARJob",.peekNewSolveId()) , jobDescription = "ProActive R Job", priority = "normal", .cancelOnError = TRUE) {  
  
  dots <- list(...)
  
  if (length(dots) == 0) {
    stop("Not enough arguments")
  }
  
  if (client == NULL || is.jnull(client) ) {
    stop("You are not currently connected to the scheduler, use PAConnect")
  } 
  
  
  cl <- class(dots[[1]])
  if ((cl == "function") || (cl == "character")) {
    # simplified syntax (a simple parametric sweep) => rebuild a new call
    answer <- do.call("PASolve",list(do.call("PA",dots,envir=parent.frame())), envir=parent.frame())
    return (answer)
  }    
  
  jobresult <- tryCatch (
{  
  .peekNewSolveId()
  job <- PAJob(jobName, jobDescription)
  setPriority(job, priority)
  setCancelJobOnError(job, .cancelOnError)
  task.names <- NULL
  all.tasks <- list()
  
  for (i in 1:length(dots)) {
    tasklist <- dots[[i]]
    
    for (j in 1:length(tasklist)) {
      .compute.task.dependencies(tasklist[[j]],environment())    
    }
  }
  # sort tasks by their names
  unordered <- unlist(lapply(task.names, function(x)strtoi(str_sub(x,2))))
  new.indexes <- sort(unordered,index.return=TRUE)
  task.names <- task.names[new.indexes[["ix"]]]
  all.tasks <- all.tasks[new.indexes[["ix"]]]
  
  for (i in 1:length(all.tasks)) {
    addTask(job) <- all.tasks[[i]]
  }
  
  if (.debug) {
    print("Submitting job : ")
    cat(toString(job))
  }
  jobid <- j_try_catch(client$submit(getJavaObject(job)))
  cat(str_c("Job submitted (id : ",jobid$value(),")","\n"," with tasks : ",toString(task.names)))
  
  jobresult <- PAJobResult(job, jobid$value(),  task.names, client)  
  .last.result <<- jobresult
  return(jobresult)
}, finally = {
  .commitNewSolveId()
})
  
};