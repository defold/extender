pipeline {
  agent {
    node {
      label 'docker'
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