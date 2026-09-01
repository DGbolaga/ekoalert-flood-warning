package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.EdgeCorrection;
import ng.ekoalert.engine.Confidence;

/**
 * What one tap on the map did.
 *
 * @param edge          the edge as it stands after the tap, or null for a
 *                      proposal that has not reached the threshold yet
 * @param distinctVoices how many separate residents have now taken this action
 * @param thresholdMet  whether this tap was the one that crossed the threshold
 * @param newConfidence the confidence after the tap, null when no edge exists yet
 */
public record CorrectionOutcome(EdgeCorrection correction,
                                DrainageEdge edge,
                                long distinctVoices,
                                boolean thresholdMet,
                                Confidence newConfidence) {
}
