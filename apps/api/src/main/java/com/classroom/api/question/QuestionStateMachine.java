package com.classroom.api.question;

import com.classroom.api.contract.QuestionState;
import java.util.Map;
import java.util.Set;

public final class QuestionStateMachine {
  private final Map<QuestionState, Set<QuestionState>> transitions = Map.of(
      QuestionState.DRAFT, Set.of(QuestionState.READY, QuestionState.CLOSED),
      QuestionState.READY, Set.of(QuestionState.BUZZ_OPEN, QuestionState.ANSWER_OPEN, QuestionState.CLOSED),
      QuestionState.BUZZ_OPEN, Set.of(QuestionState.ANSWER_OPEN, QuestionState.LOCKED, QuestionState.CLOSED),
      QuestionState.ANSWER_OPEN, Set.of(QuestionState.LOCKED, QuestionState.REVIEW, QuestionState.PUBLISHED, QuestionState.CLOSED),
      QuestionState.LOCKED, Set.of(QuestionState.REVIEW, QuestionState.PUBLISHED, QuestionState.CLOSED),
      QuestionState.REVIEW, Set.of(QuestionState.PUBLISHED, QuestionState.CLOSED),
      QuestionState.PUBLISHED, Set.of(QuestionState.CLOSED),
      QuestionState.CLOSED, Set.of()
  );

  public void assertTransitionAllowed(QuestionState current, QuestionState target) {
    Set<QuestionState> allowedTargets = transitions.getOrDefault(current, Set.of());
    if (!allowedTargets.contains(target)) {
      throw new IllegalArgumentException("Illegal state transition: " + current + " -> " + target);
    }
  }
}
