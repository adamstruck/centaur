task runMe {
  command {
    echo "done"

    sleep 2
  }
  output {
    String s = read_string(stdout())
  }
  runtime {
    docker: "ubuntu:latest"
  }
}

workflow simple_if {
  if (true) {
    call runMe as runMeTrue
  }

  if (false) {
    call runMe as runMeFalse
  }

  output {
    runMeTrue.s
    runMeFalse.s
  }
}
