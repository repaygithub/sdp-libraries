/*
  Copyright © 2020 Booz Allen Hamilton. All Rights Reserved.
  This software package is licensed under the Booz Allen Public License. The license can be found in the License file or at http://boozallen.github.io/licenses/bapl
*/
package libraries.git.steps

/*
  Validate library configuration
*/
@Init
void call(){

    /*
      define a map of distributions and a closure for their own validations
    */
    def options = [ 
        "gitlab": { c -> println "gitlab config is ${c}"}, 
        "github": { c -> println "github config is ${c}"}, 
        "github_enterprise": { c -> println "github enterprise config is ${c}"}
    ]

    def submap = config.subMap(options.keySet())
    if(submap.size() != 1){
        error "you must configure one distribution option, currently: ${submap.keySet()}" 
    }
    
    // get the distribution
    String dist = submap.keySet().first()
    // invoke the distribution closure 
    options[dist](config[dist])    

    env.GIT_LIBRARY_DISTRUBITION = dist
    this.init_env()
}

// Initialize Git configuration of env vars
void init_env(){
    node{
        try{ unstash "workspace" }
        catch(ignored) { 
          println "'workspace' stash not present. Skipping git library environment variable initialization. To change this behavior, ensure the 'sdp' library is loaded"
          return
        }

        env.GIT_URL = scm.getUserRemoteConfigs()[0].getUrl()
        env.GIT_CREDENTIAL_ID = scm.getUserRemoteConfigs()[0].credentialsId.toString()
        def parts = env.GIT_URL.split("/")
        for (part in parts){
            parts = parts.drop(1)
            if (part.contains(".")) break
        }
        env.ORG_NAME = parts.getAt(0)
        env.REPO_NAME = parts[1..-1].join("/") - ".git"
        env.GIT_SHA = sh(script: "git rev-parse HEAD", returnStdout: true).trim()

        if (env.CHANGE_TARGET){
            env.GIT_BUILD_CAUSE = "pr"
        } else {
            env.GIT_BUILD_CAUSE = sh (
              script: 'git rev-list HEAD --parents -1 | wc -w', // will have 2 shas if commit, 3 or more if merge
              returnStdout: true
            ).trim().toInteger() > 2 ? "merge" : "commit"
        }

        if (env.GIT_BUILD_CAUSE == "merge")
        if (on_merge.get_merged_from() == null) {
          println "Source branch may have been deleted.  Falling back to commit."
          env.GIT_BUILD_CAUSE = "commit"
        }

        println "Found Git Build Cause: ${env.GIT_BUILD_CAUSE}"
    }
    return
}

def fetch(){
  def stepName = env.GIT_LIBRARY_DISTRUBITION

  if(jteVersion.lessThanOrEqualTo("2.0.4")){
    return getBinding().getStep(stepName)
  } else {
    return this.getStepFromCollector(stepName)
  }
}

@NonCPS
def getStepFromCollector(String stepName){
  try {
    Class collector = Class.forName("org.boozallen.plugins.jte.init.primitives.TemplatePrimitiveCollector")
    List steps = collector.current().getSteps(stepName)

    if(steps.size() == 0){
      error "Step '${stepName}' not found."
    } else if (steps.size() > 1){
      error "Ambiguous step name '${stepName}'. Found multiple steps by that name."
    } else {
      return steps.first().getValue(null)
    }
  } catch(ClassNotFoundException ex) {
    error "can't find the TemplatePrimitiveCollector class. That's odd. current JTE version is '${jteVersion.get()}'. You should submit an issue at https://github.com/jenkinsci/templating-engine-plugin/issues/new/choose"
  }
}
