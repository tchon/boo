package com.wm.bfd.oo.workflow;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jayway.restassured.path.json.JsonPath;
import com.oo.api.OOInstance;
import com.oo.api.exception.OneOpsClientAPIException;
import com.oo.api.exception.OneOpsComponentExistException;
import com.oo.api.resource.model.RedundancyConfig;
import com.wm.bfd.oo.ClientConfig;
import com.wm.bfd.oo.LogUtils;
import com.wm.bfd.oo.utils.BFDUtils;
import com.wm.bfd.oo.yaml.Constants;
import com.wm.bfd.oo.yaml.PlatformBean;
import com.wm.bfd.oo.yaml.ScalBean;

public class BuildAllPlatforms extends AbstractWorkflow {
  final private static Logger LOG = LoggerFactory.getLogger(BuildAllPlatforms.class);
  // final private static String NAME = "ciName"; // Get component name.
  final private static String ACTIVE = "active";
  final private static String FAILED = "failed";
  final private static String NEWLINE = System.getProperty("line.separator");
  final private BFDUtils utils = new BFDUtils();
  private int retries = 3;

  public BuildAllPlatforms(OOInstance instance, ClientConfig config)
      throws OneOpsClientAPIException {
    super(instance, config);
  }

  public boolean process() throws OneOpsClientAPIException {
    this.bar.update(1, 100);
    this.createAssemblyIfNotExist();
    this.bar.update(5, 100);
    this.createPlatforms();
    this.bar.update(15, 100);
    this.updatePlatformVariables();
    this.bar.update(20, 100);
    this.createEnv();
    this.bar.update(30, 100);
    String status = this.getStatus();
    if (ACTIVE.equalsIgnoreCase(status)) {
      LogUtils.info(Constants.ACTIVE_DEPLOYMENT_EXISTING);
      return false;
    }

    if (FAILED.equalsIgnoreCase(status)) {
      LogUtils.info(Constants.FAIL_DEPLOYMENT_EXISTING);
      return false;
    }
    this.updateScaling();
    this.bar.update(50, 100);
    LogUtils.info(Constants.START_DEPLOYMENT);
    try {
      this.pullDesign();
    } catch (Exception e) {
      // Ignore
    }
    this.bar.update(70, 100);
    // Added retries
    boolean retry = true;
    while (retry && retries > 0) {
      utils.waitTimeout(2);
      try {
        this.deploy();
        retry = false;
      } catch (Exception e) {
        retries--;
      }
    }
    this.bar.update(100, 100);
    LogUtils.info(Constants.DEPLOYMENT_RUNNING);
    return true;
  }

  public boolean isPlatformExist(String platformName) throws OneOpsClientAPIException,
      OneOpsComponentExistException {
    JsonPath response = null;
    try {
      response = design.getPlatform(platformName);
    } catch (OneOpsClientAPIException e) {
      String msg = String.format("The platform %s is not exist!", platformName);
      throw new OneOpsComponentExistException(msg);
    }
    return response == null ? false : true;
  }

  @SuppressWarnings("unchecked")
  public boolean createPlatforms() throws OneOpsClientAPIException {
    List<PlatformBean> platforms = this.config.getYaml().getPlatformsList();
    Collections.sort(platforms);
    for (PlatformBean platform : platforms) {
      LogUtils.info(Constants.CREATING_PLATFORM, platform.getName());
      this.createPlatform(platform);
      if (platform.getComponents() == null)
        continue;
      for (Map.Entry<String, Object> entry : platform.getComponents().entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if (value instanceof Map) {
          this.updateComponentVariables(platform.getName(), key, (Map<String, Object>) value);
        } else {
          if (LOG.isInfoEnabled())
            LOG.info("Unknow type {}.", value.getClass());
        }
      }
    }
    return true;
  }

  public boolean createPlatform(PlatformBean platform) throws OneOpsClientAPIException {
    boolean isExist = false;
    try {
      isExist = this.isPlatformExist(platform.getName());
    } catch (OneOpsComponentExistException e) {
      // Ignore
    }
    if (!isExist) {
      JsonPath response =
          design.createPlatform(platform.getName(), platform.getPack(), platform.getPackVersion(),
              platform.getPackSource(), Constants.DESCRIPTION, Constants.DESCRIPTION);
      if (response != null)
        design.commitDesign();
      LogUtils.info(Constants.CREATING_PLATFORM_SUCCEED, platform.getName());
    } else {
      LogUtils.info(Constants.PLATFORM_EXISTING, platform.getName());
    }
    return true;

  }

  private boolean isComponentExist(String platformName, String componentName)
      throws OneOpsClientAPIException, OneOpsComponentExistException {
    boolean isExist = false;
    try {
      design.getPlatformComponent(platformName, componentName);
      isExist = true;
    } catch (OneOpsClientAPIException e) {
      // e.printStackTrace();
      throw new OneOpsComponentExistException(e.getMessage());
    }
    return isExist;
  }


  private boolean updatePlatformVariables() throws OneOpsClientAPIException {
    List<PlatformBean> platforms = this.config.getYaml().getPlatformsList();
    for (PlatformBean platform : platforms) {
      Map<String, String> secureVariables = platform.getSecureVariables();
      if (secureVariables != null && secureVariables.size() > 0)
        this.updateOrAddPlatformVariables(platform.getName(), secureVariables, true);
      Map<String, String> variables = platform.getVariables();
      if (variables != null && variables.size() > 0)
        this.updateOrAddPlatformVariables(platform.getName(), variables, false);
    }
    if (platforms.size() > 0)
      design.commitDesign();
    return true;
  }

  /**
   * Have to add isExist method later
   * 
   * @param platformName
   * @param variables
   * @param isSecure
   * @throws OneOpsClientAPIException
   */
  private void updateOrAddPlatformVariables(String platformName, Map<String, String> variables,
      boolean isSecure) throws OneOpsClientAPIException {
    try {
      design.updatePlatformVariable(platformName, variables, isSecure);
    } catch (OneOpsClientAPIException e) {
      e.printStackTrace();
      design.addPlatformVariable(platformName, variables, isSecure);
    }
  }

  /**
   * Right now support components with two layers config.
   * 
   * @param platformName
   * @param componentName
   * @param attributes
   * @throws OneOpsClientAPIException
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void updateComponentVariables(String platformName, String componentName,
      Map<String, Object> attributes) throws OneOpsClientAPIException {

    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      // Another Map, so key is ciName
      if (value instanceof Map) {
        Map<String, String> attris = (Map<String, String>) value;
        // System.out.println("In map:" + key + ":" + componentName + "; " + attris);
        this.updateComponentVariablesInternal(platformName, componentName, key, attris);
      } else if (value instanceof String) {
        Map<String, String> att = (Map) attributes;
        this.updateComponentVariablesInternal(platformName, componentName, componentName, att);
        break;
      }

    }

  }

  private boolean updateComponentVariablesInternal(String platformName, String componentName,
      String uniqueName, Map<String, String> attributes) throws OneOpsClientAPIException {
    LogUtils.info(Constants.UPDATE_COMPONENTS, componentName, platformName);
    boolean isExist = Boolean.FALSE;
    try {
      isExist = this.isComponentExist(platformName, uniqueName);
    } catch (OneOpsComponentExistException e1) {
      // Ignore
      isExist = Boolean.FALSE;
    }
    if (isExist) {
      design.updatePlatformComponent(platformName, uniqueName, attributes);
    } else {
      design.addPlatformComponent(platformName, componentName, uniqueName, attributes);
    }
    design.commitDesign();
    return true;
  }

  public String getCustomIps(String platformName, String componentName)
      throws OneOpsClientAPIException {
    return utils.getIps(platformName, componentName, this);
  }

  public String printIps(String platformName, String componentName) throws OneOpsClientAPIException {
    List<Map<String, String>> ips = this.getIpsInternal(platformName, componentName);
    StringBuilder str = new StringBuilder();
    for (Map<String, String> ip : ips) {
      str.append(ip.get(Constants.PRIVATE_IP));
      str.append(NEWLINE);
    }
    return str.toString();
  }

  public boolean updateScaling() throws OneOpsClientAPIException {
    List<ScalBean> scales = this.config.getYaml().getScales();
    for (ScalBean scale : scales) {
      RedundancyConfig config = new RedundancyConfig();
      config.setCurrent(scale.getCurrent());
      config.setMin(scale.getMin());
      config.setMax(scale.getMax());
      LogUtils.info(Constants.COMPUTE_SIZE, envName, scale.getPlatform());
      transition.updatePlatformRedundancyConfig(envName, scale.getPlatform(), scale.getComponent(),
          config);
    }
    transition.commitEnvironment(envName, null, Constants.DESCRIPTION);
    return true;
  }

}
