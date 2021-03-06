package com.openshift.jenkins.plugins.pipeline;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.restclient.IClient;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.model.IDeploymentConfig;
import com.openshift.restclient.model.IReplicationController;

import javax.servlet.ServletException;

import java.io.IOException;
import java.util.Map;

public class OpenShiftDeploymentVerifier extends OpenShiftBaseStep {

	protected final static String DISPLAY_NAME = "Verify OpenShift Deployment";
	
    protected final String depCfg;
    protected final String replicaCount;
    protected final String verifyReplicaCount;
    
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftDeploymentVerifier(String apiURL, String depCfg, String namespace, String replicaCount, String authToken, String verbose, String verifyReplicaCount) {
    	super(apiURL, namespace, authToken, verbose);
        this.depCfg = depCfg;
        this.replicaCount = replicaCount;
        this.verifyReplicaCount = verifyReplicaCount;
    }

	public String getDepCfg() {
		return depCfg;
	}

	public String getDepCfg(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("depCfg"))
			return overrides.get("depCfg");
		return getDepCfg();
	}
	
	public String getReplicaCount() {
		return replicaCount;
	}
	
	public String getReplicaCount(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("replicaCount"))
			return overrides.get("replicaCount");
		return getReplicaCount();
	}
	
	public String getVerifyReplicaCount() {
		return verifyReplicaCount;
	}
	
	public String getVerifyReplicaCount(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("verifyReplicaCount"))
			return overrides.get("verifyReplicaCount");
		return getVerifyReplicaCount();
	}
	
	public boolean coreLogic(Launcher launcher, TaskListener listener,
			EnvVars env, Map<String,String> overrides) {
    	boolean chatty = Boolean.parseBoolean(getVerbose(overrides));
    	boolean checkCount = Boolean.parseBoolean(getVerifyReplicaCount(overrides));
    	listener.getLogger().println(String.format(MessageConstants.START_DEPLOY_RELATED_PLUGINS, DISPLAY_NAME, getDepCfg(overrides), getNamespace(overrides)));
    	
    	// get oc client 
    	IClient client = this.getClient(listener, DISPLAY_NAME, overrides);
    	
    	if (client != null) {
        	// explicitly set replica count, save that
        	int count = -1;
        	if (checkCount && getReplicaCount(overrides) != null && getReplicaCount(overrides).length() > 0)
        		count = Integer.parseInt(getReplicaCount(overrides));
        		

        	if (!checkCount)
        		listener.getLogger().println(String.format(MessageConstants.WAITING_ON_DEPLOY, getDepCfg(overrides)));
        	else
        		listener.getLogger().println(String.format(MessageConstants.WAITING_ON_DEPLOY_PLUS_REPLICAS, getDepCfg(overrides), getReplicaCount(overrides)));        	
			
			// confirm the deployment has kicked in from completed build;
        	// in testing with the jenkins-ci sample, the initial deploy after
        	// a build is kinda slow ... gotta wait more than one minute
			long currTime = System.currentTimeMillis();
			String state = null;
			String depId = null;
        	boolean scaledAppropriately = false;
			if (chatty)
				listener.getLogger().println("\nOpenShiftDeploymentVerifier wait " + getDescriptor().getWait());
			while (System.currentTimeMillis() < (currTime + getDescriptor().getWait())) {
				// refresh dc first
				IDeploymentConfig dc = client.get(ResourceKind.DEPLOYMENT_CONFIG, getDepCfg(overrides), getNamespace(overrides));
				
				if (dc != null) {
					// if replicaCount not set, get it from config
					if (checkCount && count == -1)
						count = dc.getReplicas();
					
					if (chatty)
						listener.getLogger().println("\nOpenShiftDeploymentVerifier latest version:  " + dc.getLatestVersionNumber());
									
					IReplicationController rc = getLatestReplicationController(dc, client, overrides);
						
					if (rc != null) {
						if (chatty)
							listener.getLogger().println("\nOpenShiftDeploymentVerifier current rc " + rc);
						state = this.getReplicationControllerState(rc);
						depId = rc.getName();
						// first check state
		        		if (state.equalsIgnoreCase("Failed")) {
	        		    	listener.getLogger().println(String.format(MessageConstants.EXIT_DEPLOY_RELATED_PLUGINS_BAD, DISPLAY_NAME, getDepCfg(overrides), state));
		        			return false;
		        		}
						if (chatty) listener.getLogger().println("\nOpenShiftDeploymentVerifier rc current count " + rc.getCurrentReplicaCount() + " rc desired count " + rc.getDesiredReplicaCount() + " step verification amount " + count + " current state " + state + " and check count " + checkCount);
						
						scaledAppropriately = this.isReplicationControllerScaledAppropriately(rc, checkCount, count);
						if (scaledAppropriately)
							break;
		        		
					}
				} else {
		    		listener.getLogger().println(String.format(MessageConstants.EXIT_DEPLOY_RELATED_PLUGINS_NO_CFG, DISPLAY_NAME, getDepCfg(overrides)));
	    			return false;
				}
													        										
        		try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}

			}
        			
        	if (scaledAppropriately) {
    	    	if (!checkCount)
    	    		listener.getLogger().println(String.format(MessageConstants.EXIT_DEPLOY_RELATED_PLUGINS_GOOD_REPLICAS_IGNORED, DISPLAY_NAME, depId));
    	    	else
    	    		listener.getLogger().println(String.format(MessageConstants.EXIT_DEPLOY_VERIFY_GOOD_REPLICAS_GOOD, DISPLAY_NAME, depId, count));
        		return true;
        	} else {
        		if (checkCount)
        			listener.getLogger().println(String.format(MessageConstants.EXIT_DEPLOY_VERIFY_BAD_REPLICAS_BAD, DISPLAY_NAME, depId, getReplicaCount(overrides)));
        		else
    		    	listener.getLogger().println(String.format(MessageConstants.EXIT_DEPLOY_RELATED_PLUGINS_BAD, DISPLAY_NAME, depId, state));
    	    	return false;
        	}        	
        		
        		
    	} else {
    		return false;
    	}

	}

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link OpenShiftDeploymentVerifier}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
    	private long wait = 180000;
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the various fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApiURL(@QueryParameter String value)
                throws IOException, ServletException {
        	return ParamVerify.doCheckApiURL(value);
        }

        public FormValidation doCheckDepCfg(@QueryParameter String value)
                throws IOException, ServletException {
        	return ParamVerify.doCheckDepCfg(value);
        }

        public FormValidation doCheckNamespace(@QueryParameter String value)
                throws IOException, ServletException {
        	return ParamVerify.doCheckNamespace(value);
        }
        
        
        public FormValidation doCheckReplicaCount(@QueryParameter String value)
                throws IOException, ServletException {
        	return ParamVerify.doCheckReplicaCount(value);
        }


        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return DISPLAY_NAME;
        }
        
        public long getWait() {
        	return wait;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
        	wait = formData.getLong("wait");
            save();
            return super.configure(req,formData);
        }

    }

}

