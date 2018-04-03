package com.voicebase.gateways.awsconnect.forward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.voicebase.api.model.VbMetricGroupConfiguration;
import com.voicebase.gateways.awsconnect.forward.CallbackProvider;
import com.voicebase.gateways.awsconnect.forward.MediaProcessingRequestBuilder;
import com.voicebase.gateways.awsconnect.lambda.Lambda;
import com.voicebase.sdk.v3.MediaProcessingRequest;

public class MediaProcessingRequestBuilderTest {

  @Test
  public void testAdvancedFeatures() {
    CallbackProvider cbp = new CallbackProvider();
    cbp.setCallbackMethod("POST");
    cbp.setCallbackUrl("http://example.com");
    cbp.setIncludes(new String[] {});
    MediaProcessingRequestBuilder builder = new MediaProcessingRequestBuilder().withCallbackProvider(cbp)
        .withConfigureSpeakers(true).withPredictionsEnabled(true).withKnowledgeDiscoveryEnabled(true)
        .withAdvancedPunctuationEnabled(true)
        .withAwsInputData(Collections.singletonMap(Lambda.KEY_EXTERNAL_ID, (Object) "externalId"))
        .withLeftSpeakerName("left").withRightSpeakerName("right");

    MediaProcessingRequest req = builder.build();

    Assert.assertTrue(req.getConfiguration().getSpeechModel().getFeatures()
        .contains(MediaProcessingRequestBuilder.SPEECH_FEATURE_ADVANCED_PUNCTUATION));

  }

  @Test
  public void testVoiceFeatures() {
    CallbackProvider cbp = new CallbackProvider();
    cbp.setCallbackMethod("POST");
    cbp.setCallbackUrl("http://example.com");
    cbp.setIncludes(new String[] {});

    HashMap<String, Object> awsAttr = new HashMap<>();
    awsAttr.put(Lambda.KEY_EXTERNAL_ID, (Object) "externalId");
    HashMap<String, String> vbAttr = new HashMap<>();
    awsAttr.put(Lambda.KEY_ATTRIBUTES, vbAttr);

    vbAttr.put("voicebase_classifier_names", "blah");

    MediaProcessingRequestBuilder builder = new MediaProcessingRequestBuilder().withCallbackProvider(cbp)
        .withConfigureSpeakers(true).withPredictionsEnabled(true).withKnowledgeDiscoveryEnabled(true)
        .withAdvancedPunctuationEnabled(true).withAwsInputData(awsAttr).withLeftSpeakerName("left")
        .withRightSpeakerName("right");

    MediaProcessingRequest req = builder.build();

    Assert.assertTrue(req.getConfiguration().getSpeechModel().getFeatures()
        .contains(MediaProcessingRequestBuilder.SPEECH_FEATURE_VOICE));

  }

  @Test
  public void testMetricsConfig() {
    CallbackProvider cbp = new CallbackProvider();
    cbp.setCallbackMethod("POST");
    cbp.setCallbackUrl("http://example.com");
    cbp.setIncludes(new String[] {});

    HashMap<String, Object> awsAttr = new HashMap<>();
    awsAttr.put(Lambda.KEY_EXTERNAL_ID, (Object) "externalId");
    HashMap<String, String> vbAttr = new HashMap<>();
    awsAttr.put(Lambda.KEY_ATTRIBUTES, vbAttr);

    vbAttr.put("voicebase_metrics_groups", "overtalk,sentiment,talk-style-tone-and-volume");

    MediaProcessingRequestBuilder builder = new MediaProcessingRequestBuilder().withCallbackProvider(cbp)
        .withConfigureSpeakers(true).withPredictionsEnabled(true).withKnowledgeDiscoveryEnabled(true)
        .withAdvancedPunctuationEnabled(true).withAwsInputData(awsAttr).withLeftSpeakerName("left")
        .withRightSpeakerName("right");

    MediaProcessingRequest req = builder.build();

    List<VbMetricGroupConfiguration> metrics = req.getConfiguration().getMetrics();
    Assert.assertNotNull(metrics);
    Assert.assertEquals(3, metrics.size());

    ArrayList<String> groups = new ArrayList<>();
    for (VbMetricGroupConfiguration metric : metrics) {
      groups.add(metric.getMetricGroupName());
    }

    Assert.assertTrue(groups.contains("overtalk"));
    Assert.assertTrue(groups.contains("sentiment"));
    Assert.assertTrue(groups.contains("talk-style-tone-and-volume"));

  }

}
