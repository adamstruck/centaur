task head {
  File inputFile

  command {
     head ${inputFile}
     sleep 2
  }
  output {
    String headOut = read_string(stdout())
  }
  runtime { docker: "ubuntu:latest" }
}

workflow missing_inputs_sub_wf {
    File f
    call head { input: inputFile = f }
    output {
      head.headOut
    }
}