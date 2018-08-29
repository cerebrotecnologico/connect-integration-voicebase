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
package com.voicebase.gateways.awsconnect.response;

import static com.voicebase.gateways.awsconnect.ConfigUtil.getStringSetting;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicebase.gateways.awsconnect.ConfigUtil;
import com.voicebase.gateways.awsconnect.lambda.Lambda;
import com.voicebase.v3client.JacksonFactory;
import com.voicebase.v3client.datamodel.VbMedia;
import java.nio.ByteBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author Volker Kueffel <volker@voicebase.com> */
public class TranscriptionForwarder {

  private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptionForwarder.class);

  private String transcriptOutputStream;
  private boolean addNewlineToOutput = false;

  private final ObjectMapper objectMapper;
  private final AmazonKinesis kinesisClient;

  public TranscriptionForwarder(Map<String, String> env) {
    objectMapper = JacksonFactory.objectMapper();
    kinesisClient = AmazonKinesisClientBuilder.defaultClient();
    configure(env);
  }

  public void forward(String processingResult) {

    byte[] msgData = createOutdata(processingResult);
    String mediaId = null;
    String externalId = null;

    VbMedia result = null;
    try {
      result = objectMapper.readValue(processingResult.getBytes(), VbMedia.class);
      mediaId = result.getMediaId();
      externalId = result.getMetadata().getExternalId();
    } catch (Exception e) {
      LOGGER.debug("Problem accessing mediaId/externalId", e);
    }

    LOGGER.info("Transcript for call ID {}, media ID {} received.", externalId, mediaId);

    if (msgData != null) {
      String partitionKey = "1";
      if (externalId != null) {
        partitionKey = externalId;
      } else if (mediaId != null) {
        partitionKey = mediaId;
      }
      try {

        kinesisClient.putRecord(
            new PutRecordRequest()
                .withStreamName(transcriptOutputStream)
                .withData(ByteBuffer.wrap(msgData))
                .withPartitionKey(partitionKey));
        LOGGER.debug(
            "Transcript for call ID {}, media ID {} sent to {}",
            externalId,
            mediaId,
            transcriptOutputStream);
        LOGGER.trace("VB API processing result: {}", processingResult);
      } catch (Exception e) {
        LOGGER.error("Unable to write result to Kinesis", e);
        // re-throw to let the callback try again
        throw e;
      }

    } else {
      LOGGER.warn("No usable data received, not writing output to stream");
    }
  }

  private byte[] createOutdata(String processingResult) {
    if (processingResult == null) {
      return null;
    }

    try {
      // deserialize/serialize to remove any existing JSON formatting
      Map<?, ?> deserialized = objectMapper.readValue(processingResult.getBytes(), Map.class);
      if (!addNewlineToOutput) {
        return objectMapper.writeValueAsBytes(deserialized);
      } else {
        return new String(objectMapper.writeValueAsString(deserialized) + "\n").getBytes();
      }
    } catch (Exception e) {
      LOGGER.warn("Unable to deserialize/serialize response, sending original", e);
    }

    if (!addNewlineToOutput) {
      return processingResult.getBytes();
    } else {
      return new String(processingResult + "\n").getBytes();
    }
  }

  void configure(Map<String, String> env) {
    transcriptOutputStream = getStringSetting(env, Lambda.ENV_TRANSCRIPT_OUTPUT_STREAM, null);
    addNewlineToOutput =
        ConfigUtil.getBooleanSetting(
            env,
            Lambda.ENV_TRANSCRIPT_OUTPUT_ADD_NEWLINE,
            Lambda.DEFAULT_TRANSCRIPT_OUTPUT_ADD_NEWLINE);
  }
}
