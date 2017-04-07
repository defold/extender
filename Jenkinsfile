pipeline {
  agent {
    node {
      label 'linux docker'
    }
    
  }
  stages {
    stage('Build') {
      steps {
        sh './server/scripts/build.sh'
      }
    }
  }
}