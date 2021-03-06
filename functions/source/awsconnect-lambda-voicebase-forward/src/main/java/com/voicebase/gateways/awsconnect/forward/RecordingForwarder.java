/**
 * Copyright 2016-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved. Licensed under the
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

import static com.voicebase.gateways.awsconnect.ConfigUtil.getBooleanSetting;
import static com.voicebase.gateways.awsconnect.ConfigUtil.getIntSetting;
import static com.voicebase.gateways.awsconnect.ConfigUtil.getLongSetting;
import static com.voicebase.gateways.awsconnect.ConfigUtil.getStringListSetting;
import static com.voicebase.gateways.awsconnect.ConfigUtil.getStringSetting;
import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.getBooleanParameter;
import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.getS3RecordingLocation;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor;
import com.voicebase.gateways.awsconnect.lambda.Lambda;
import com.voicebase.sdk.v3.MediaProcessingRequest;
import com.voicebase.sdk.v3.ServiceFactory;
import com.voicebase.sdk.v3.VoiceBaseClient;

/**
 * 
 * @author Volker Kueffel <volker@voicebase.com>
 *
 */
public class RecordingForwarder {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecordingForwarder.class);

  // my settings
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
  private VoiceBaseClient voicebaseClient;
  private CallbackProvider callbackProvider;


  RecordingForwarder() {
    this(System.getenv());
  }

  public RecordingForwarder(Map<String, String> env) {
    s3Client = AmazonS3ClientBuilder.defaultClient();
    configure(env);
  }

  public boolean forwardRequest(Map<String, Object> dataAsMap) {
    boolean forwarded = false;
    try {

      Object externalId = dataAsMap.get(Lambda.KEY_EXTERNAL_ID);

      if (externalId != null) {

        if (!shouldProcess(dataAsMap)) {
          LOGGER.info("CTR with ContactID {} should not be processed, skipping.", externalId);
          return false;
        }

        MediaProcessingRequestBuilder builder = new MediaProcessingRequestBuilder()
            .withCallbackProvider(callbackProvider).withConfigureSpeakers(configureSpeakers)
            .withPredictionsEnabled(predictionsEnabled)
            .withKnowledgeDiscoveryEnabled(knowledgeEnabled)
            .withAdvancedPunctuationEnabled(advancedPunctuationEnabld).withAwsInputData(dataAsMap)
            .withLeftSpeakerName(leftSpeakerName).withRightSpeakerName(rightSpeakerName);

        MediaProcessingRequest req = builder.build();

        String s3Location = getS3RecordingLocation(dataAsMap);

        if (s3Location != null) {
          String parts[] = s3Location.split("/", 2);
          String preSignedUrl = createPresignedUrl(parts[0], parts[1], mediaUrlTtl);
          req.setMediaUrl(preSignedUrl);
          String mediaId =
              voicebaseClient.uploadMedia(vbApiToken, req, vbApiRetryAttempts, vbApiRetryDelay);
          if (mediaId != null) {
            LOGGER.info("Call ID {} sent for processing; mediaId={}", externalId, mediaId);
            forwarded = true;
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


  boolean shouldProcess(Map<String, Object> dataAsMap) {
    try {
      VoiceBaseAttributeExtractor mc = VoiceBaseAttributeExtractor.fromAwsInputData(dataAsMap);
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


  /**
   * Create a pre-signed URL for given S3 bucket, object key and time to live.
   * 
   * @param bucketName S3 bucket containing the object
   * @param objectKey S3 object key
   * @param ttl time to live for the pre-signed URL.
   * 
   * @return pre-signed URL
   * 
   * @throws SdkClientException if pre-signing the URL failed.
   * @throws IllegalArgumentException if bucket name or object key is empty
   */
  private String createPresignedUrl(String bucketName, String objectKey, long ttl)
      throws SdkClientException, IllegalArgumentException {
    if (StringUtils.isEmpty(bucketName) || StringUtils.isEmpty(objectKey)) {
      LOGGER.error("Need bucket and key to create presigned URL. Bucket: {}, key: {}", bucketName,
          objectKey);
      throw new IllegalArgumentException("Bucket name or object key missing.");
    }

    long msec = System.currentTimeMillis() + ttl;
    Date expiration = new Date(msec);

    GeneratePresignedUrlRequest generatePresignedUrlRequest =
        new GeneratePresignedUrlRequest(bucketName, objectKey).withMethod(HttpMethod.GET)
            .withExpiration(expiration);

    try {
      URL s = s3Client.generatePresignedUrl(generatePresignedUrlRequest);
      return s.toExternalForm();
    } catch (SdkClientException e) {
      LOGGER.error("Unable to generate pre-signed URL to call recording, bucket {}, key {}.",
          bucketName, objectKey, e);
      throw e;
    }
  }

  void configure(Map<String, String> env) {
    configureSpeakers = getBooleanSetting(env, Lambda.ENV_CONFIGURE_SPEAKERS, true);
    predictionsEnabled = getBooleanSetting(env, Lambda.ENV_ENABLE_PREDICTIONS, true);
    knowledgeEnabled = getBooleanSetting(env, Lambda.ENV_ENABLE_KNOWLEDGE_DISCOVERY,
        Lambda.DEFAULT_ENABLE_KNOWLEDGE_DISCOVERY);
    advancedPunctuationEnabld = getBooleanSetting(env, Lambda.ENV_ENABLE_ADVANCED_PUNCTUATION,
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
    callbackIncludes = getStringListSetting(env, Lambda.ENV_CALLBACK_INCLUDES,
        Lambda.DEFAULT_CALLBACK_INCLUDES_V3);
    additionalCallbackUrls = getStringListSetting(env, Lambda.ENV_CALLBACK_ADDITIONAL_URLS, null);

    callbackProvider = new CallbackProvider();
    callbackProvider.setIncludes(callbackIncludes);
    callbackProvider.setCallbackMethod(callbackMethod);
    callbackProvider.setCallbackUrl(callbackUrl);
    callbackProvider.setAdditionalCallbackUrls(additionalCallbackUrls);

    voicebaseClient = ServiceFactory.voicebaseClient(vbApiUrl, vbApiClientLogLevel);
  }

}
