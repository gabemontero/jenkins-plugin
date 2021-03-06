package com.openshift.jenkins.plugins.pipeline;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.jboss.dmr.ModelNode;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.internal.restclient.http.HttpClientException;
import com.openshift.internal.restclient.http.UrlConnectionHttpClient;
import com.openshift.internal.restclient.model.ImageStream;
import com.openshift.internal.restclient.model.KubernetesResource;
import com.openshift.restclient.ClientBuilder;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.authorization.TokenAuthorizationStrategy;
import com.openshift.restclient.model.IImageStream;

import javax.servlet.ServletException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class OpenShiftImageTagger extends OpenShiftBaseStep {

	protected final static String DISPLAY_NAME = "Tag OpenShift Image";
	
    protected final String testTag;
    protected final String prodTag;
    protected final String testStream;
    protected final String prodStream;
    protected final String destinationNamespace;
    protected final String destinationAuthToken;
    // marked transient so don't serialize these next 2 in the workflow plugin flow; constructed on per request basis
    protected transient TokenAuthorizationStrategy destinationBearerToken;
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftImageTagger(String apiURL, String testTag, String prodTag, String namespace, String authToken, String verbose, String testStream, String prodStream, String destinationNamespace, String destinationAuthToken) {
    	super(apiURL, namespace, authToken, verbose);
        this.testTag = testTag;
        this.prodTag = prodTag;
        this.prodStream = prodStream;
        this.testStream = testStream;
        this.destinationAuthToken = destinationAuthToken;
        this.destinationNamespace = destinationNamespace;
    }

	public String getTestTag() {
		return testTag;
	}

	public String getTestTag(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("testTag"))
			return overrides.get("testTag");
		return getTestTag();
	}
	
	public String getProdTag() {
		return prodTag;
	}
	
	public String getProdTag(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("prodTag"))
			return overrides.get("prodTag");
		return getProdTag();
	}
	
	public String getTestStream() {
		return testStream;
	}

	public String getTestStream(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("testStream"))
			return overrides.get("testStream");
		return getTestStream();
	}
	
	public String getProdStream() {
		return prodStream;
	}

	public String getProdStream(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("prodStream"))
			return overrides.get("prodStream");
		return getProdStream();
	}
	
	public String getDestinationNamespace() {
		return this.destinationNamespace;
	}
	
	public String getDestinationNamespace(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("destinationNamespace"))
			return overrides.get("destinationNamespace");
		return getDestinationNamespace();
	}
	
	public String getDestinationAuthToken() {
		return this.destinationAuthToken;
	}
	
	public String getDestinationAuthToken(Map<String,String> overrides) {
		if (overrides != null && overrides.containsKey("destinationAuthToken"))
			return overrides.get("destinationAuthToken");
		return getDestinationAuthToken();
	}
	
	
	
	@Override
	public void pullDefaultsIfNeeded(EnvVars env,
			HashMap<String, String> overrides, TaskListener listener) {
    	boolean chatty = Boolean.parseBoolean(getVerbose());
    	if (chatty)
    		listener.getLogger().println(" before pull defaults destination namespace " + getDestinationNamespace() + " env " + env);
		if ((getDestinationNamespace() == null || getDestinationNamespace().length() == 0) && !overrides.containsKey("destinationNamespace")) {
			overrides.put("destinationNamespace", env.get("PROJECT_NAME"));
		}
		
		super.pullDefaultsIfNeeded(env, overrides, listener);
	}

	public boolean coreLogic(Launcher launcher, TaskListener listener,
			EnvVars env, Map<String,String> overrides) {
    	listener.getLogger().println(String.format(MessageConstants.START_TAG, getTestStream(overrides), getTestTag(overrides), getNamespace(overrides), getProdStream(overrides), getProdTag(overrides), getDestinationNamespace(overrides)));
    	boolean chatty = Boolean.parseBoolean(getVerbose(overrides));
    	
    	// get oc clients 
    	IClient client = this.getClient(listener, DISPLAY_NAME, overrides);
   		destinationBearerToken = new TokenAuthorizationStrategy(Auth.deriveBearerToken(null, getDestinationAuthToken(overrides), listener, chatty));
    	
    	if (client != null) {
    		// get src id
    		IImageStream srcIS = client.get(ResourceKind.IMAGE_STREAM, getTestStream(overrides), getNamespace(overrides));
    		if (srcIS == null) {
    			listener.getLogger().println(String.format(MessageConstants.EXIT_TAG_CANNOT_GET_IS, getTestStream(overrides), getNamespace(overrides)));
    			return false;
    		}
        	String srcImageID = getTestStream(overrides) + "@" + srcIS.getImageId(getTestTag(overrides)).substring(7); // starting at 7 lops off "sha256:"
        	
        	if (chatty)
        		listener.getLogger().println("\n srcImageID " + srcImageID);
        	
        	//get dest image stream
			IImageStream destIS = null;
			
			String destinationNS = null;
			if (getDestinationNamespace(overrides).length() == 0) {
				destinationNS = getNamespace(overrides);
			} else {
				destinationNS = getDestinationNamespace(overrides);
			}
			
        	if (getNamespace(overrides).equals(destinationNS)) {
        		destIS = srcIS;
        	} else {
    			try {
    				destIS = client.get(ResourceKind.IMAGE_STREAM, getProdStream(overrides), destinationNS);
    			} catch (com.openshift.restclient.OpenShiftException e) {
    				String createJson = "{\"kind\": \"ImageStream\",\"apiVersion\": \"v1\",\"metadata\": {\"name\": \"" +
    				getProdStream(overrides) + "\",\"creationTimestamp\": null},\"spec\": {},\"status\": {\"dockerImageRepository\": \"\"}}";
    				
    				Map<String,String> newOverrides = new HashMap<String,String>(overrides);
    				newOverrides.remove("namespace");
    				OpenShiftCreator isCreator = new OpenShiftCreator(getApiURL(newOverrides), destinationNS, destinationBearerToken.getToken(), getVerbose(newOverrides), createJson);
    				isCreator.setAuth(Auth.createInstance(chatty ? listener : null, getApiURL(newOverrides), env));
    		    	isCreator.setToken(destinationBearerToken);
    				
    				
    				boolean newISCreated = isCreator.coreLogic(launcher, listener, env, newOverrides);

    				if (!newISCreated) {
    					listener.getLogger().println(String.format(MessageConstants.EXIT_TAG_CANNOT_CREATE_DEST_IS, getProdStream(overrides), destinationNS));
    					return false;
    				}
    				destIS = client.get(ResourceKind.IMAGE_STREAM, getProdStream(overrides), destinationNS);
    			}
        	}
        	
        	if (destIS == null) {
    			listener.getLogger().println(String.format(MessageConstants.EXIT_TAG_CANNOT_GET_IS, getProdStream(overrides), destinationNS));
    			return false;
        	}
        	
        	// tag image
        	destIS.addTag(getProdTag(overrides), "ImageStreamImage", srcImageID, getNamespace(overrides));
			if (chatty)
				listener.getLogger().println("\n updated image stream json " + ((ImageStream)destIS).getNode().toJSONString(false));
			client.update(destIS);
			
			
	    	listener.getLogger().println(String.format(MessageConstants.EXIT_OK, DISPLAY_NAME));
			return true;
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
     * Descriptor for {@link OpenShiftImageTagger}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
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

        public FormValidation doCheckNamespace(@QueryParameter String value)
                throws IOException, ServletException {
            return ParamVerify.doCheckNamespace(value);
        }
        
        public FormValidation doCheckDestinationNamespace(@QueryParameter String value)
                throws IOException, ServletException {
        	// change reuse doCheckNamespace for destination namespace
            return ParamVerify.doCheckNamespace(value);
        }

        public FormValidation doCheckTestTag(@QueryParameter String value)
                throws IOException, ServletException {
            return ParamVerify.doCheckTestTag(value);
        }

        public FormValidation doCheckProdTag(@QueryParameter String value)
                throws IOException, ServletException {
            return ParamVerify.doCheckProdTag(value);
        }


        public FormValidation doCheckTestStream(@QueryParameter String value)
                throws IOException, ServletException {
            return ParamVerify.doCheckTestStream(value);
        }

        public FormValidation doCheckProdStream(@QueryParameter String value)
                throws IOException, ServletException {
            return ParamVerify.doCheckProdStream(value);
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

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
            save();
            return super.configure(req,formData);
        }

    }

}

