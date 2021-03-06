package com.openshift.jenkins.plugins.pipeline;

import java.io.IOException;
import java.io.Serializable;

import com.openshift.restclient.authorization.TokenAuthorizationStrategy;

import jenkins.tasks.SimpleBuildStep;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;

public abstract class OpenShiftBasePostAction extends Recorder implements SimpleBuildStep, Serializable, IOpenShiftPlugin {

    protected final String apiURL;
    protected final String namespace;
    protected final String authToken;
    protected final String verbose;
    protected transient TokenAuthorizationStrategy bearerToken;
    protected transient Auth auth;

    public OpenShiftBasePostAction(String apiURL, String namespace, String authToken, String verbose) {
    	this.apiURL = apiURL;
    	this.namespace = namespace;
    	this.authToken = authToken;
    	this.verbose = verbose;
	}

    public String getApiURL() {
		return apiURL;
	}

	public String getNamespace() {
		return namespace;
	}
	
	public String getAuthToken() {
		return authToken;
	}
	
    public String getVerbose() {
		return verbose;
	}
    
    @Override
	public void setAuth(Auth auth) {
		this.auth = auth;
	}

	@Override
	public void setToken(TokenAuthorizationStrategy token) {
		this.bearerToken = token;
	}
    
    @Override
	public Auth getAuth() {
		return auth;
	}

	@Override
	public TokenAuthorizationStrategy getToken() {
		return bearerToken;
	}

	@Override
	public String getBaseClassName() {
		return OpenShiftBasePostAction.class.getName();
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}
	  
    // this is the workflow plugin path
	@Override
	public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher,
			TaskListener listener) throws InterruptedException, IOException {
		this.doIt(run, workspace, launcher, listener);
	}

	// this is the classic post build action path
	@Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		return this.doIt(build, launcher, listener);
    }


}
