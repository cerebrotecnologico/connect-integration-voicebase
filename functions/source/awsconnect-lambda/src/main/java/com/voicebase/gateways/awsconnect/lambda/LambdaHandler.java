/**
 * Copyright 2016-${year} Amazon.com, Inc. or its affiliates. All Rights Reserved. Licensed under the
 * Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with
 * the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.voicebase.gateways.awsconnect.lambda;

import com.google.common.base.Splitter;
import com.voicebase.gateways.awsconnect.LogConfigurer;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author volker@voicebase.com */
public abstract class LambdaHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(LambdaHandler.class);
  private static final String PROPERTY_KEY_VERSION = "application.version";
  private static final String RUNTIME_ID = ManagementFactory.getRuntimeMXBean().getName();

  protected static final Splitter CSV_SPLITTER = Splitter.on(",").omitEmptyStrings().trimResults();
  protected static final String DEFAULT_PROPERTIES = "lambda.properties";

  protected abstract void configure(Map<String, String> env);

  private Properties handlerProperties = null;

  /**
   * Constructor.
   *
   * <p>Perfoms the following tasks:
   *
   * <ol>
   *   <li>Configure logging ({@link #configureLogging(Map)})
   *   <li>Load the default properties file ({@link #loadProperties()})
   *   <li>Print out a start message {{@link #sayHello()})
   *   <li>Configure the handler ({@link #configure(Map)})
   * </ol>
   *
   * @param env handler environment
   */
  protected LambdaHandler(Map<String, String> env) {
    configureLogging(env);
    loadProperties();
    sayHello();
    configure(env);
  }

  protected void configureLogging(Map<String, String> env) {
    if (env != null) {
      String logConfig = env.get(Lambda.ENV_LOG_CONFIG);
      LogConfigurer.configure(logConfig);
    }
  }

  protected Properties getHandlerProperties() {
    if (handlerProperties == null) {
      loadProperties();
    }
    return handlerProperties;
  }

  protected void loadProperties() {
    handlerProperties = new Properties();
    try {
      handlerProperties.load(getClass().getClassLoader().getResourceAsStream(DEFAULT_PROPERTIES));
    } catch (Exception e) {
      LOGGER.warn("Unable to load default properties from {}", DEFAULT_PROPERTIES, e);
    }
  }

  protected void sayHello() {
    LOGGER.info(
        "This is {} version {} running on JVM {}",
        getClass().getSimpleName(),
        getHandlerProperties().getProperty(PROPERTY_KEY_VERSION, "<UNKNOWN>"),
        RUNTIME_ID);
  }
}
