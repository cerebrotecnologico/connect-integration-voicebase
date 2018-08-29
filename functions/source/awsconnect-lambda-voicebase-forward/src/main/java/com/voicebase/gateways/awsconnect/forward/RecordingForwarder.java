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
package com.voicebase.gateways.awsconnect.forward;

import static com.voicebase.gateways.awsconnect.ConfigUtil.*;
import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.*;

import com.amazonaws.AmazonClientException;
import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicebase.gateways.awsconnect.BeanFactory;
import com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor;
import com.voicebase.gateways.awsconnect.lambda.Lambda;
import com.voicebase.sdk.v3.MediaProcessingRequest;
import com.voicebase.sdk.v3.ServiceFactory;
import com.voicebase.sdk.v3.VoiceBaseClient;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordingForwarder {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecordingForwarder.class);

  // my settings
  private final ExponentialBackoffSettings expBackoffSettings = new ExponentialBackoffSettings();

  private int maximumRedeliveries = Lambda.DEFAULT_MAX_REDELIVERIES;
  private boolean configureSpeakers;
  private boolean predictionsEnabled;
  private boolean knowledgeEnabled;
  private boolean advancedPunctuationEnabld;
  private String leftSpeakerName;
  private String rightSpeakerName;
  private long mediaUrlTtl;
  private String vbApiUrl;
  private String vbApiClientLogLevel;
  private String vbApiToken;
  private long vbApiRetryDelay;
  private int vbApiRetryAttempts;
  private String callbackUrl;
  private String callbackMethod;
  private List<String> callbackIncludes;
  private List<String> additionalCallbackUrls;

  private final AmazonS3 s3Client;
  private final AmazonSQS sqsClient;
  private VoiceBaseClient voicebaseClient;
  private CallbackProvider callbackProvider;
  private String sqsQueueUrl;

  RecordingForwarder() {
    this(System.getenv());
  }

  public RecordingForwarder(Map<String, String> env) {
    s3Client = AmazonS3ClientBuilder.defaultClient();
    sqsClient = AmazonSQSClientBuilder.defaultClient();
    configure(env);
  }

  public boolean forwardRequest(Map<String, Object> ctrAsMap) {
    boolean forwarded = false;
    try {

      Object externalId = ctrAsMap.get(Lambda.KEY_EXTERNAL_ID);

      if (externalId != null) {

        if (!shouldProcess(ctrAsMap)) {
          LOGGER.info("CTR with ContactID {} should not be processed, skipping.", externalId);
          return false;
        }

        MediaProcessingRequestBuilder builder =
            new MediaProcessingRequestBuilder()
                .withCallbackProvider(callbackProvider)
                .withConfigureSpeakers(configureSpeakers)
                .withPredictionsEnabled(predictionsEnabled)
                .withKnowledgeDiscoveryEnabled(knowledgeEnabled)
                .withAdvancedPunctuationEnabled(advancedPunctuationEnabld)
                .withAwsInputData(ctrAsMap)
                .withLeftSpeakerName(leftSpeakerName)
                .withRightSpeakerName(rightSpeakerName);

        MediaProcessingRequest req = builder.build();

        String s3Location = getS3RecordingLocation(ctrAsMap);

        if (s3Location != null) {
          String parts[] = s3Location.split("/", 2);
          String bucketName = parts[0];
          String objectKey = parts[1];
          if (verifyAudioAvailability(ctrAsMap, bucketName, objectKey)) {
            String preSignedUrl = createPresignedUrl(bucketName, objectKey, mediaUrlTtl);
            req.setMediaUrl(preSignedUrl);
            String mediaId =
                voicebaseClient.uploadMedia(vbApiToken, req, vbApiRetryAttempts, vbApiRetryDelay);
            if (mediaId != null) {
              LOGGER.info("Call ID {} sent for processing; mediaId={}", externalId, mediaId);
              forwarded = true;
            }
          } else {
            int currentRetries = getRedeliveryCount(ctrAsMap);
            if (currentRetries >= maximumRedeliveries) {
              LOGGER.warn(
                  "CTR {} contains audio location, but audio file is not yet available. Redeliveries exceeded.Skipping...",
                  externalId);
            } else {
              delayProcessing(currentRetries, ctrAsMap, externalId);
            }
          }
        } else {
          LOGGER.warn("CTR {} doesn't contain an audio location. Skipping...", externalId);
        }
      } else {
        LOGGER.info("Received record without contact ID, not a CTR record. Skipping...");
      }
    } catch (SdkClientException | IllegalArgumentException e) {
      LOGGER.warn("Skipping record, unable to generate pre-signed URL.", e);
    } catch (IOException e) {
      LOGGER.error("Error sending media to VB API", e);
    } catch (Exception e) {
      LOGGER.error("Unexpected error", e);
    }
    return forwarded;
  }

  void delayProcessing(int currentRetries, Map<String, Object> ctrAsMap, Object externalId) {
    try {
      LOGGER.info("CTR {} Redelivery count: {}", externalId, currentRetries + 1);
      int delay = computeDelay(currentRetries + 1);
      setRedeliveryCount(ctrAsMap, currentRetries + 1);
      LOGGER.info(
          "CTR {} contains audio location, but audio file is not yet available.  Queueing into SQS with delay set to {} seconds.",
          externalId,
          delay);
      ObjectMapper om = BeanFactory.objectMapper();
      String payload = om.writeValueAsString(ctrAsMap);
      SendMessageRequest request = new SendMessageRequest(sqsQueueUrl, payload);
      request.setDelaySeconds(delay);
      sqsClient.sendMessage(request);
    } catch (AmazonClientException e) {
      LOGGER.warn("Unable to queue for delayed processing ({})", externalId);
    } catch (JsonProcessingException ex) {
      LOGGER.warn("Unable to serialize to JSON string ({})", externalId);
    }
  }

  int computeDelay(int redeliveryCount) {
    if (redeliveryCount == 1) {
      return expBackoffSettings.getInitialDelayInSeconds();
    }
    double delay =
        expBackoffSettings.getInitialDelayInSeconds()
            * Math.pow(expBackoffSettings.getExponentialFactor(), redeliveryCount - 1);
    if (delay > expBackoffSettings.getMaximumAllowedDelayInSeconds()) {
      return expBackoffSettings.getMaximumAllowedDelayInSeconds();
    }
    return (int) Math.floor(delay);
  }

  boolean verifyAudioAvailability(Map<String, Object> ctrAsMap, String bucket, String key) {
    try {
      VoiceBaseAttributeExtractor mc = VoiceBaseAttributeExtractor.fromAwsInputData(ctrAsMap);
      int failTimes =
          getIntegerParameter(
              mc.immutableSubset(Lambda.X_VB_ATTR), Lambda.X_VB_ATTR_TIMES_TO_FAIL_AUDIO_EXISTS, 0);
      int redeliveryCount = getRedeliveryCount(ctrAsMap);
      if (redeliveryCount <= failTimes) {
        return false;
      }
    } catch (Exception e) {
      LOGGER.warn(
          "Can't read VoiceBase attributes to determine if VoiceBase should simulate failure.");
    }
    return verifyS3ResourceAvailability(bucket, key);
  }

  boolean verifyS3ResourceAvailability(String bucket, String key) {
    LOGGER.info("Looking up for file on bucket: {} key: {}", bucket, key);
    boolean result = false;
    try {
      result = s3Client.doesObjectExist(bucket, key);
    } catch(SdkClientException e) {
      LOGGER.info("Unable to perform s3Client.doesObjectExist: {}", e.getMessage());
    }
    return result;
  }

  boolean shouldProcess(Map<String, Object> ctrAsMap) {
    try {
      VoiceBaseAttributeExtractor mc = VoiceBaseAttributeExtractor.fromAwsInputData(ctrAsMap);
      if (!getBooleanParameter(mc.immutableSubset(Lambda.VB_ATTR), Lambda.VB_ATTR_ENABLE, true)) {
        LOGGER.info("VoiceBase processing disabled by flow.");
        return false;
      }
    } catch (Exception e) {
      LOGGER.warn(
          "Can't read VoiceBase attributes to determine if VoiceBase processing is enabled. Trying to process anyways...");
    }
    return true;
  }

  int getRedeliveryCount(Map<String, Object> ctrAsMap) {
    int cnt = 0;
    try {
      VoiceBaseAttributeExtractor mc = VoiceBaseAttributeExtractor.fromAwsInputData(ctrAsMap);
      cnt =
          getIntegerParameter(
              mc.immutableSubset(Lambda.VB_ATTR), Lambda.VB_ATTR_REDELIVERY_COUNT, 0);
    } catch (Exception e) {
      LOGGER.warn("Can't read VoiceBase attribute to determine retries. Assuming zero...");
    }
    if (cnt < 0) {
      cnt = 0;
    }
    return cnt;
  }

  void setRedeliveryCount(Map<String, Object> ctrAsMap, int redeliveryCount) {
    try {
      Map<String, Object> attributes = (Map<String, Object>) ctrAsMap.get(Lambda.KEY_ATTRIBUTES);
      if (attributes == null) {
        attributes = new LinkedHashMap<>();
        ctrAsMap.put(Lambda.KEY_ATTRIBUTES, attributes);
      }
      attributes.put(Lambda.VB_ATTR + "_" + Lambda.VB_ATTR_REDELIVERY_COUNT, redeliveryCount);
    } catch (ClassCastException e) {
      LOGGER.warn("Attributes is not a map");
    }
  }

  /**
   * Create a pre-signed URL for given S3 bucket, object key and time to live.
   *
   * @param bucketName S3 bucket containing the object
   * @param objectKey S3 object key
   * @param ttl time to live for the pre-signed URL.
   * @return pre-signed URL
   * @throws SdkClientException if pre-signing the URL failed.
   * @throws IllegalArgumentException if bucket name or object key is empty
   */
  private String createPresignedUrl(String bucketName, String objectKey, long ttl)
      throws SdkClientException, IllegalArgumentException {
    if (StringUtils.isEmpty(bucketName) || StringUtils.isEmpty(objectKey)) {
      LOGGER.error(
          "Need bucket and key to create presigned URL. Bucket: {}, key: {}",
          bucketName,
          objectKey);
      throw new IllegalArgumentException("Bucket name or object key missing.");
    }

    long msec = System.currentTimeMillis() + ttl;
    Date expiration = new Date(msec);

    GeneratePresignedUrlRequest generatePresignedUrlRequest =
        new GeneratePresignedUrlRequest(bucketName, objectKey)
            .withMethod(HttpMethod.GET)
            .withExpiration(expiration);

    try {
      URL s = s3Client.generatePresignedUrl(generatePresignedUrlRequest);
      return s.toExternalForm();
    } catch (SdkClientException e) {
      LOGGER.error(
          "Unable to generate pre-signed URL to call recording, bucket {}, key {}.",
          bucketName,
          objectKey,
          e);
      throw e;
    }
  }

  void configure(Map<String, String> env) {

    expBackoffSettings.setInitialDelayInSeconds(
        getIntSetting(
            env,
            Lambda.ENV_EXP_BACKOFF_INITIAL_DELAY,
            ExponentialBackoffSettings.DEFAULT_INITIAL_DELAY_SECS));
    expBackoffSettings.setExponentialFactor(
        getDoubleSetting(
            env,
            Lambda.ENV_EXP_BACKOFF_FACTOR,
            ExponentialBackoffSettings.DEFAULT_EXPONENTIAL_FACTOR));
    expBackoffSettings.setMaximumAllowedDelayInSeconds(
        getIntSetting(
            env,
            Lambda.ENV_EXP_BACKOFF_MAX_DELAY,
            ExponentialBackoffSettings.MAXIMUM_ALLOWED_DELAY_SECS));
    maximumRedeliveries =
        getIntSetting(env, Lambda.ENV_MAX_REDELIVERIES, Lambda.DEFAULT_MAX_REDELIVERIES);
    sqsQueueUrl = getStringSetting(env, Lambda.ENV_DELAYED_QUEUE_SQS_URL, null);

    configureSpeakers = getBooleanSetting(env, Lambda.ENV_CONFIGURE_SPEAKERS, true);
    predictionsEnabled = getBooleanSetting(env, Lambda.ENV_ENABLE_PREDICTIONS, true);
    knowledgeEnabled =
        getBooleanSetting(
            env, Lambda.ENV_ENABLE_KNOWLEDGE_DISCOVERY, Lambda.DEFAULT_ENABLE_KNOWLEDGE_DISCOVERY);
    advancedPunctuationEnabld =
        getBooleanSetting(
            env,
            Lambda.ENV_ENABLE_ADVANCED_PUNCTUATION,
            Lambda.DEFAULT_ENABLE_ADVANCED_PUNCTUATION);
    leftSpeakerName =
        getStringSetting(env, Lambda.ENV_LEFT_SPEAKER, Lambda.DEFAULT_LEFT_SPEAKER_NAME);
    rightSpeakerName =
        getStringSetting(env, Lambda.ENV_RIGHT_SPEAKER, Lambda.DEFAULT_RIGHT_SPEAKER_NAME);
    mediaUrlTtl =
        getLongSetting(env, Lambda.ENV_MEDIA_URL_TTL_MILLIS, Lambda.DEFAULT_MEDIA_URL_TTL_MILLIS);

    vbApiUrl = getStringSetting(env, Lambda.ENV_API_URL, Lambda.DEFAULT_V3_API_URL);
    vbApiClientLogLevel =
        getStringSetting(env, Lambda.ENV_API_CLIENT_LOGLEVEL, Lambda.DEFAULT_API_CLIENT_LOG_LEVEL);
    vbApiToken = getStringSetting(env, Lambda.ENV_API_TOKEN, null);
    vbApiRetryDelay =
        getLongSetting(env, Lambda.ENV_API_RETRY_DELAY, Lambda.DEFAULT_API_RETRY_DELAY);
    vbApiRetryAttempts =
        getIntSetting(env, Lambda.ENV_API_RETRY_ATTEMPTS, Lambda.DEFAULT_API_RETRY_ATTEMPTS);

    callbackUrl = getStringSetting(env, Lambda.ENV_CALLBACK_URL, null);
    callbackMethod =
        getStringSetting(env, Lambda.ENV_CALLBACK_METHOD, Lambda.DEFAULT_CALLBACK_METHOD);
    callbackIncludes =
        getStringListSetting(
            env, Lambda.ENV_CALLBACK_INCLUDES, Lambda.DEFAULT_CALLBACK_INCLUDES_V3);
    additionalCallbackUrls = getStringListSetting(env, Lambda.ENV_CALLBACK_ADDITIONAL_URLS, null);
    callbackProvider = new CallbackProvider();
    callbackProvider.setIncludes(callbackIncludes);
    callbackProvider.setCallbackMethod(callbackMethod);
    callbackProvider.setCallbackUrl(callbackUrl);
    callbackProvider.setAdditionalCallbackUrls(additionalCallbackUrls);

    voicebaseClient = ServiceFactory.voicebaseClient(vbApiUrl, vbApiClientLogLevel);
  }
}
