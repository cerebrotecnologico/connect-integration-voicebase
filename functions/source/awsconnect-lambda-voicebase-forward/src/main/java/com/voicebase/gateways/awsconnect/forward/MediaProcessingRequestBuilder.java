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

import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.getBooleanParameter;
import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.getStringParameter;
import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.getStringParameterSet;
import static com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor.getVoicebaseAttributeName;

import com.google.common.collect.Lists;
import com.voicebase.gateways.awsconnect.VoiceBaseAttributeExtractor;
import com.voicebase.gateways.awsconnect.lambda.Lambda;
import com.voicebase.sdk.v3.MediaProcessingRequest;
import com.voicebase.v3client.datamodel.VbAudioRedactorConfiguration;
import com.voicebase.v3client.datamodel.VbCallbackConfiguration;
import com.voicebase.v3client.datamodel.VbChannelConfiguration;
import com.voicebase.v3client.datamodel.VbClassifierConfiguration;
import com.voicebase.v3client.datamodel.VbConfiguration;
import com.voicebase.v3client.datamodel.VbContentFilteringConfiguration;
import com.voicebase.v3client.datamodel.VbDetectorConfiguration;
import com.voicebase.v3client.datamodel.VbFormattingConfiguration;
import com.voicebase.v3client.datamodel.VbHttpMethodEnum;
import com.voicebase.v3client.datamodel.VbIncludeTypeEnum;
import com.voicebase.v3client.datamodel.VbIngestConfiguration;
import com.voicebase.v3client.datamodel.VbKnowledgeConfiguration;
import com.voicebase.v3client.datamodel.VbMetadata;
import com.voicebase.v3client.datamodel.VbMetricGroupConfiguration;
import com.voicebase.v3client.datamodel.VbParameter;
import com.voicebase.v3client.datamodel.VbPredictionConfiguration;
import com.voicebase.v3client.datamodel.VbPriorityEnum;
import com.voicebase.v3client.datamodel.VbPublishConfiguration;
import com.voicebase.v3client.datamodel.VbRedactorConfiguration;
import com.voicebase.v3client.datamodel.VbSpeechModelConfiguration;
import com.voicebase.v3client.datamodel.VbSpottingConfiguration;
import com.voicebase.v3client.datamodel.VbSpottingGroupConfiguration;
import com.voicebase.v3client.datamodel.VbTranscriptConfiguration;
import com.voicebase.v3client.datamodel.VbTranscriptRedactorConfiguration;
import com.voicebase.v3client.datamodel.VbVocabularyConfiguration;
import com.voicebase.v3client.datamodel.VbVocabularyTermConfiguration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.configuration2.ImmutableConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MediaProcessingRequestBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(MediaProcessingRequestBuilder.class);

  static final String SPEECH_FEATURE_VOICE = "voiceFeatures";
  static final String SPEECH_FEATURE_ADVANCED_PUNCTUATION = "advancedPunctuation";
  static final String REDACTION_REPLACEMENT = "[redacted]";
  static final Float REDACTION_GAIN = 0.5f;
  static final Integer REDACTION_TONE = 270;
  static final String DETECTOR_NAME_PCI = "PCI";
  static final String DETECTOR_PCI_PARAM_DETECTION_LEVEL_VALUE = "probableNumbers";
  static final String DETECTOR_PCI_PARAM_DETECTION_LEVEL_NAME = "detectionLevel";
  static final String DETECTOR_NAME_NUMBER = "Number";

  private Map<String, Object> awsInputData;

  private static final VbRedactorConfiguration DEFAULT_REDACTOR_CONFIG =
      new VbRedactorConfiguration()
          .transcript(new VbTranscriptRedactorConfiguration().replacement(REDACTION_REPLACEMENT))
          .audio(new VbAudioRedactorConfiguration().tone(REDACTION_TONE).gain(REDACTION_GAIN));

  private boolean predictionsEnabled = true;
  private boolean knowledgeDiscoveryEnabled = false;
  private boolean advancedPunctuationEnabled = true;
  private boolean configureSpeakers = true;

  private String leftSpeakerName;
  private String rightSpeakerName;

  private CallbackProvider callbackProvider;

  private String externalId;
  private VbConfiguration configuration;
  private VbMetadata metaData;
  private MediaProcessingRequest request;

  public void setAwsInputData(Map<String, Object> dataAsMap) {
    this.awsInputData = dataAsMap;
  }

  public MediaProcessingRequestBuilder withAwsInputData(Map<String, Object> dataAsMap) {
    setAwsInputData(dataAsMap);
    return this;
  }

  Map<String, Object> getAwsInputData() {
    return this.awsInputData;
  }

  public void setPredictionsEnabled(boolean predictionsEnabled) {
    this.predictionsEnabled = predictionsEnabled;
  }

  public MediaProcessingRequestBuilder withPredictionsEnabled(boolean predictionsEnabled) {
    setPredictionsEnabled(predictionsEnabled);
    return this;
  }

  public boolean isKnowledgeDiscoveryEnabled() {
    return knowledgeDiscoveryEnabled;
  }

  public void setKnowledgeDiscoveryEnabled(boolean knowledgeEnabled) {
    this.knowledgeDiscoveryEnabled = knowledgeEnabled;
  }

  public boolean isAdvancedPunctuationEnabled() {
    return advancedPunctuationEnabled;
  }

  public void setAdvancedPunctuationEnabled(boolean advancedPunctuationEnabled) {
    this.advancedPunctuationEnabled = advancedPunctuationEnabled;
  }

  public MediaProcessingRequestBuilder withAdvancedPunctuationEnabled(
      boolean advancedPunctuationEnabled) {
    setAdvancedPunctuationEnabled(advancedPunctuationEnabled);
    return this;
  }

  public MediaProcessingRequestBuilder withKnowledgeDiscoveryEnabled(boolean knowledgeEnabled) {
    setKnowledgeDiscoveryEnabled(knowledgeEnabled);
    return this;
  }

  public void setConfigureSpeakers(boolean configureSpeakers) {
    this.configureSpeakers = configureSpeakers;
  }

  public MediaProcessingRequestBuilder withConfigureSpeakers(boolean configureSpeakers) {
    setConfigureSpeakers(configureSpeakers);
    return this;
  }

  public void setLeftSpeakerName(String leftSpeakerName) {
    this.leftSpeakerName = leftSpeakerName;
  }

  public MediaProcessingRequestBuilder withLeftSpeakerName(String leftSpeakerName) {
    setLeftSpeakerName(leftSpeakerName);
    return this;
  }

  public void setRightSpeakerName(String rightSpeakerName) {
    this.rightSpeakerName = rightSpeakerName;
  }

  public void setCallbackProvider(CallbackProvider callbackProvider) {
    this.callbackProvider = callbackProvider;
  }

  public MediaProcessingRequestBuilder withCallbackProvider(CallbackProvider callbackProvider) {
    setCallbackProvider(callbackProvider);
    return this;
  }

  public MediaProcessingRequestBuilder withRightSpeakerName(String rightSpeakerName) {
    setRightSpeakerName(rightSpeakerName);
    return this;
  }

  public String getExternalId() {
    return externalId;
  }

  public VbConfiguration getConfiguration() {
    return configuration;
  }

  public VbMetadata getMetaData() {
    return metaData;
  }

  public MediaProcessingRequest getRequest() {
    return request;
  }

  public MediaProcessingRequest build() {
    externalId = awsInputData.get(Lambda.KEY_EXTERNAL_ID).toString();
    configuration = createConfiguration();
    metaData = createMetaData();

    request = new MediaProcessingRequest();
    request.setConfiguration(configuration);
    request.setMetadata(metaData);

    return request;
  }

  private VbMetadata createMetaData() {
    VbMetadata vbMetadata = new VbMetadata();
    vbMetadata.setExternalId(externalId);
    vbMetadata.setExtended(awsInputData);
    return vbMetadata;
  }

  /**
   * Create VB configuration out of Lily message.
   *
   * <p>NOTE: As a side effect some of the attributes in the map are rewritten with expanded lists.
   *
   * @return VB configuration
   */
  private VbConfiguration createConfiguration() {

    VbConfiguration vbConfiguration = new VbConfiguration();
    VbIngestConfiguration vbIngestConfiguration = new VbIngestConfiguration();
    VbTranscriptConfiguration vbTranscriptConfiguration = new VbTranscriptConfiguration();
    VbKnowledgeConfiguration vbKnowledgeConfiguration = new VbKnowledgeConfiguration();
    vbKnowledgeConfiguration.enableDiscovery(knowledgeDiscoveryEnabled);
    VbSpeechModelConfiguration vbSpeechModelConfiguration = new VbSpeechModelConfiguration();
    VbPublishConfiguration vbPublishConfiguration = new VbPublishConfiguration();
    VbPredictionConfiguration vbPredictionConfiguration = new VbPredictionConfiguration();

    // speakers
    if (configureSpeakers) {
      VbChannelConfiguration leftChannelConfig =
          new VbChannelConfiguration().speakerName(leftSpeakerName);
      VbChannelConfiguration rightChannelConfig =
          new VbChannelConfiguration().speakerName(rightSpeakerName);
      vbIngestConfiguration.channels(Lists.newArrayList(leftChannelConfig, rightChannelConfig));
    }

    ArrayList<String> speechFeatures = new ArrayList<>();
    speechFeatures.add(SPEECH_FEATURE_VOICE);
    if (advancedPunctuationEnabled) {
      speechFeatures.add(SPEECH_FEATURE_ADVANCED_PUNCTUATION);
    }
    vbSpeechModelConfiguration.features(speechFeatures);

    // callbacks
    List<VbIncludeTypeEnum> includes = new ArrayList<>();
    if (callbackProvider.hasIncludes()) {
      for (String include : callbackProvider.getIncludes()) {
        if (!StringUtils.isEmpty(include)) {
          VbIncludeTypeEnum includeEnum = VbIncludeTypeEnum.fromValue(include);
          if (includeEnum != null) {
            includes.add(includeEnum);
          }
        }
      }
    }

    List<VbCallbackConfiguration> callbacks = new ArrayList<>();
    callbacks.add(
        (new VbCallbackConfiguration())
            .url(callbackProvider.getCallbackUrl())
            .method(VbHttpMethodEnum.valueOf(callbackProvider.getCallbackMethod()))
            .include(includes));
    if (callbackProvider.hasAdditionalCallbackUrls()) {
      for (String callback : callbackProvider.getAdditionalCallbackUrls()) {
        callbacks.add(
            new VbCallbackConfiguration()
                .url(callback)
                .method(VbHttpMethodEnum.valueOf(callbackProvider.getCallbackMethod()))
                .include(includes));
      }
    }
    vbPublishConfiguration.callbacks(callbacks);

    Map<String, Object> vbLabsConfiguration = new LinkedHashMap<>();
    Map<String, Object> vbVoiceActivityConfiguration = new LinkedHashMap<>();
    vbVoiceActivityConfiguration.put("enableVoiceActivity", Boolean.TRUE);
    vbLabsConfiguration.put("voiceActivity", vbVoiceActivityConfiguration);

    @SuppressWarnings("unchecked")
    Map<String, Object> attributes = (Map<String, Object>) awsInputData.get(Lambda.KEY_ATTRIBUTES);
    if (attributes != null && !attributes.isEmpty()) {
      VoiceBaseAttributeExtractor mc = new VoiceBaseAttributeExtractor(attributes);

      ImmutableConfiguration vbAttrs = mc.immutableSubset(Lambda.VB_ATTR);

      List<VbDetectorConfiguration> detectors = new ArrayList<>();
      Set<String> detectorsAdded = new HashSet<>();

      boolean redactPCI =
          getBooleanParameter(
              vbAttrs, Lambda.VB_ATTR_PCIREDACT, Lambda.DEFAULT_ENABLE_PCI_REDACTION);
      if (redactPCI) {
        VbDetectorConfiguration pciDetectorConfiguration =
            new VbDetectorConfiguration()
                .detectorName(DETECTOR_NAME_PCI)
                .parameters(
                    Collections.singletonList(
                        new VbParameter()
                            .parameter(DETECTOR_PCI_PARAM_DETECTION_LEVEL_NAME)
                            .value(DETECTOR_PCI_PARAM_DETECTION_LEVEL_VALUE)))
                .redactor(DEFAULT_REDACTOR_CONFIG);
        detectors.add(pciDetectorConfiguration);
        detectorsAdded.add(pciDetectorConfiguration.getDetectorName());
      }

      boolean redactNumbers =
          getBooleanParameter(
              vbAttrs, Lambda.VB_ATTR_NUMBERREDACT, Lambda.DEFAULT_ENABLE_NUMBER_REDACTION);
      if (redactNumbers) {
        VbDetectorConfiguration numberDetectorConfiguration =
            new VbDetectorConfiguration()
                .detectorName(DETECTOR_NAME_NUMBER)
                .redactor(DEFAULT_REDACTOR_CONFIG);
        detectors.add(numberDetectorConfiguration);
        detectorsAdded.add(numberDetectorConfiguration.getDetectorName());
      }

      String commaSeparatedDectectorNames =
          VoiceBaseAttributeExtractor.getStringParameter(vbAttrs, Lambda.VB_ATTR_REDACTORS);
      if (!StringUtils.isBlank(commaSeparatedDectectorNames)) {
        String[] detectorNames = StringUtils.split(commaSeparatedDectectorNames, ',');
        for (String detectorName : detectorNames) {
          detectorName = detectorName.trim();
          if (!detectorsAdded.contains(detectorName)) {
            VbDetectorConfiguration detectorConfiguration =
                new VbDetectorConfiguration()
                    .detectorName(detectorName)
                    .redactor(DEFAULT_REDACTOR_CONFIG);
            detectors.add(detectorConfiguration);
            detectorsAdded.add(detectorConfiguration.getDetectorName());
          }
        }
      }

      if (!detectors.isEmpty()) {
        vbPredictionConfiguration.detectors(detectors);
      }

      String priorityString = getStringParameter(vbAttrs, Lambda.VB_ATTR_PRIORIY);
      try {
        VbPriorityEnum p = VbPriorityEnum.fromValue(priorityString);
        if (p == null) {
          p = VbPriorityEnum.NORMAL;
        }
        vbConfiguration.priority(p);
      } catch (Exception e) {
        LOGGER.error(
            "Unknown priority '{}' for ext ID {}",
            vbAttrs.getString(Lambda.VB_ATTR_PRIORIY),
            awsInputData.get(Lambda.KEY_EXTERNAL_ID));
        vbConfiguration.priority(VbPriorityEnum.NORMAL);
      }

      ImmutableConfiguration transcriptAttr = vbAttrs.immutableSubset(Lambda.VB_ATTR_TRANSCRIPT);

      vbTranscriptConfiguration.formatting(
          new VbFormattingConfiguration()
              .enableNumberFormatting(
                  getBooleanParameter(transcriptAttr, Lambda.VB_ATTR_TRANSCRIPT_NUMBER_FORMAT)));

      vbTranscriptConfiguration.contentFiltering(
          new VbContentFilteringConfiguration()
              .enableProfanityFiltering(
                  getBooleanParameter(transcriptAttr, Lambda.VB_ATTR_TRANSCRIPT_SWEARWORD_FILTER)));

      // knowledge discovery
      ImmutableConfiguration knowledgeAttr = vbAttrs.immutableSubset(Lambda.VB_ATTR_KNOWLEDGE);
      Boolean knowledgeDiscoveryEnabledAttr =
          getBooleanParameter(knowledgeAttr, Lambda.VB_ATTR_KNOWLEDGE_DISCOVERY);
      if (knowledgeDiscoveryEnabledAttr != null) {
        vbKnowledgeConfiguration.enableDiscovery(knowledgeDiscoveryEnabledAttr);
      }

      // phrase spotting
      ImmutableConfiguration keywordAttr = vbAttrs.immutableSubset(Lambda.VB_ATTR_KEYWORDS);

      Set<String> groups = getStringParameterSet(keywordAttr, Lambda.VB_ATTR_KEYWORDS_GROUPS);
      if (groups != null && !groups.isEmpty()) {

        List<VbSpottingGroupConfiguration> spottingGroups = new ArrayList<>();
        for (String groupName : groups) {
          spottingGroups.add(new VbSpottingGroupConfiguration().groupName(groupName));
        }

        vbConfiguration.spotting(new VbSpottingConfiguration().groups(spottingGroups));

        // overwrite metadata
        attributes.put(
            getVoicebaseAttributeName(Lambda.VB_ATTR_KEYWORDS, Lambda.VB_ATTR_KEYWORDS_GROUPS),
            groups);
      }

      vbSpeechModelConfiguration.language(getStringParameter(vbAttrs, Lambda.VB_ATTR_LANGUAGE));

      // classifiers
      if (predictionsEnabled) {

        ImmutableConfiguration classificationAttr =
            vbAttrs.immutableSubset(Lambda.VB_ATTR_CLASSIFIER);
        Set<String> classifierNames =
            getStringParameterSet(classificationAttr, Lambda.VB_ATTR_CLASSIFIER_NAMES);
        if (classifierNames != null && !classifierNames.isEmpty()) {
          List<VbClassifierConfiguration> classifierConfigs = new ArrayList<>();

          for (String classifier : classifierNames) {
            classifierConfigs.add(new VbClassifierConfiguration().classifierName(classifier));
          }

          vbPredictionConfiguration.classifiers(classifierConfigs);

          attributes.put(
              getVoicebaseAttributeName(Lambda.VB_ATTR_CLASSIFIER, Lambda.VB_ATTR_CLASSIFIER_NAMES),
              classifierNames);
        }
      }

      // custom vocabularies
      List<VbVocabularyConfiguration> vocabs = new ArrayList<>();

      ImmutableConfiguration vocabAttr = vbAttrs.immutableSubset(Lambda.VB_ATTR_VOCABULARY);
      // vocab terms need to be unique
      Set<String> terms = getStringParameterSet(vocabAttr, Lambda.VB_ATTR_VOCABULARY_TERMS);
      if (terms != null && !terms.isEmpty()) {
        VbVocabularyConfiguration vbVocabularyConfiguration = new VbVocabularyConfiguration();
        ArrayList<VbVocabularyTermConfiguration> vocabTermConfigs = new ArrayList<>();
        for (String term : terms) {
          vocabTermConfigs.add(new VbVocabularyTermConfiguration().term(term));
        }
        vbVocabularyConfiguration.terms(vocabTermConfigs);
        vocabs.add(vbVocabularyConfiguration);

        // overwrite metadata
        attributes.put(
            getVoicebaseAttributeName(Lambda.VB_ATTR_VOCABULARY, Lambda.VB_ATTR_VOCABULARY_TERMS),
            terms);
      }

      Set<String> vocabNames = getStringParameterSet(vocabAttr, Lambda.VB_ATTR_VOCABULARY_NAMES);
      if (vocabNames != null && !vocabNames.isEmpty()) {

        for (String vocab : vocabNames) {
          vocabs.add(new VbVocabularyConfiguration().vocabularyName(vocab));
        }
        // overwrite metadata
        attributes.put(
            getVoicebaseAttributeName(Lambda.VB_ATTR_VOCABULARY, Lambda.VB_ATTR_VOCABULARY_NAMES),
            vocabNames);
      }

      if (!vocabs.isEmpty()) {
        vbConfiguration.vocabularies(vocabs);
      }

      // metrics
      ImmutableConfiguration metricsAttrs = vbAttrs.immutableSubset(Lambda.VB_ATTR_METRICS);
      Set<String> metricGroups = getStringParameterSet(metricsAttrs, Lambda.VB_ATTR_METRICS_GROUPS);
      if (metricGroups != null && !metricGroups.isEmpty()) {
        List<VbMetricGroupConfiguration> metricsConfs = new ArrayList<>();
        for (String metricGroupName : metricGroups) {
          VbMetricGroupConfiguration vbMetricGroupConfiguration = new VbMetricGroupConfiguration();
          vbMetricGroupConfiguration.metricGroupName(metricGroupName);
          metricsConfs.add(vbMetricGroupConfiguration);
        }
        vbConfiguration.metrics(metricsConfs);
      }
    }

    vbConfiguration
        .ingest(vbIngestConfiguration)
        .publish(vbPublishConfiguration)
        .transcript(vbTranscriptConfiguration)
        .speechModel(vbSpeechModelConfiguration)
        .prediction(vbPredictionConfiguration)
        .knowledge(vbKnowledgeConfiguration)
        .labs(vbLabsConfiguration);

    return vbConfiguration;
  }
}
