package com.classroom.api.submission;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.buzz.BuzzAttemptService;
import com.classroom.api.contract.QuestionState;
import com.classroom.api.contract.QuestionType;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.QuestionItemEntity;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.model.SheetConfigEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.BuzzRoundRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.question.QuestionPackageService;
import com.classroom.api.score.ClassroomScoringPolicy;
import com.classroom.api.sheets.GoogleSheetsSyncService;
import com.classroom.api.sheets.SheetAnswerFormatter;
import com.classroom.api.util.JsonMapParser;
import com.classroom.api.util.JsonParser;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SubmissionController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final QuestionRepository questionRepository;
  private final SubmissionRepository submissionRepository;
  private final ScoreRepository scoreRepository;
  private final SheetConfigRepository sheetConfigRepository;
  private final UserRepository userRepository;
  private final RealtimeEventBroker eventBroker;
  private final GoogleSheetsSyncService googleSheetsSyncService;
  private final BuzzRoundRepository buzzRoundRepository;
  private final BuzzAttemptService buzzAttemptService;
  private final QuestionItemRepository questionItemRepository;
  private final QuestionPackageService questionPackageService;

  public SubmissionController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      SubmissionRepository submissionRepository,
      ScoreRepository scoreRepository,
      SheetConfigRepository sheetConfigRepository,
      UserRepository userRepository,
      RealtimeEventBroker eventBroker,
      GoogleSheetsSyncService googleSheetsSyncService,
      BuzzRoundRepository buzzRoundRepository,
      BuzzAttemptService buzzAttemptService,
      QuestionItemRepository questionItemRepository,
      QuestionPackageService questionPackageService
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.submissionRepository = submissionRepository;
    this.scoreRepository = scoreRepository;
    this.sheetConfigRepository = sheetConfigRepository;
    this.userRepository = userRepository;
    this.eventBroker = eventBroker;
    this.googleSheetsSyncService = googleSheetsSyncService;
    this.buzzRoundRepository = buzzRoundRepository;
    this.buzzAttemptService = buzzAttemptService;
    this.questionItemRepository = questionItemRepository;
    this.questionPackageService = questionPackageService;
  }

  public HttpHandlerFunction submitHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.STUDENT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = requireClassroom(request.get("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), request.get("questionId"));
      QuestionState state = QuestionState.valueOf(question.state());
      if (state != QuestionState.ANSWER_OPEN && state != QuestionState.BUZZ_OPEN) {
        throw new IllegalArgumentException("Question is not accepting answers");
      }

      UserEntity user = requireUser(authContext.subject());
      int roundNumber = QuestionType.valueOf(question.type()) == QuestionType.BUZZ
          ? buzzRoundRepository.current(question.id()) : 0;
      boolean winnerSubmission = state == QuestionState.BUZZ_OPEN
          && QuestionType.valueOf(question.type()) == QuestionType.BUZZ;
      if (!winnerSubmission && submissionRepository.hasSubmission(question.id(), user.id(), roundNumber)) {
        throw new IllegalArgumentException("Duplicate submission is not allowed");
      }

      Instant now = Instant.now();
      String answer = request.getOrDefault("answer", "");
      SubmissionDecision decision = decide(classroom, question, answer);
      SubmissionEntity submission = new SubmissionEntity(
          UUID.randomUUID(),
          classroom.id(),
          question.id(),
          user.id(),
          1,
          answer,
          "RECEIVED",
          decision.isCorrect(),
          decision.awardedScore(),
          decision.reviewStatus(),
          now,
          decision.reviewStatus() == null ? now : null,
          SheetAnswerFormatter.extractGroupNumber(user.displayName()),
          false,
          false,
          false
      );
      BuzzAttemptService.SubmissionResult buzzResult = null;
      if (winnerSubmission) {
        buzzResult = buzzAttemptService.savePendingSubmission(submission, roundNumber);
        if (!buzzResult.accepted()) {
          HttpResponses.json(exchange, 200, Map.of(
              "accepted", false,
              "outcomeStatus", buzzResult.outcomeStatus(),
              "groupNumber", submission.groupNumber() == null ? 0 : submission.groupNumber()
          ));
          return;
        }
      } else {
        submissionRepository.save(submission, roundNumber);
      }
      if (!winnerSubmission) syncSubmissionToSheet(question, user, submission);

      if (decision.awardedScore() != null) {
        int runningTotal = scoreRepository.currentTotal(classroom.id(), user.id()) + decision.awardedScore();
        scoreRepository.save(new ScoreRecordEntity(
            UUID.randomUUID(),
            classroom.id(),
            user.id(),
            question.id(),
            "ANSWER_SCORE",
            decision.awardedScore(),
            runningTotal,
            now
        ));
      }
      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_CREATED", Map.of(
          "submissionId", submission.id().toString(),
          "questionId", question.id().toString(),
          "roundId", Integer.toString(roundNumber),
          "userId", user.id().toString(),
          "externalUserId", user.externalId(),
          "displayName", user.displayName(),
          "status", submission.submissionStatus(),
          "reviewStatus", decision.reviewStatus() == null ? "" : decision.reviewStatus()
      ));
      eventBroker.publish(classroom.classroomCode(), "QUESTION_STATISTICS_UPDATED", Map.of(
          "questionId", question.id().toString(),
          "submissionCount", submissionRepository.listByQuestionId(question.id()).size()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "accepted", true,
          "outcomeStatus", winnerSubmission ? "PENDING_REVIEW" : "ACCEPTED",
          "submissionId", submission.id().toString(),
          "isCorrect", decision.isCorrect() == null ? "" : decision.isCorrect().toString(),
          "awardedScore", decision.awardedScore() == null ? "" : decision.awardedScore().toString(),
          "reviewStatus", decision.reviewStatus() == null ? "" : decision.reviewStatus(),
          "groupNumber", submission.groupNumber() == null ? 0 : submission.groupNumber(),
          "groupSuccessfulBuzzCount", 0
      ));
    };
  }

  public HttpHandlerFunction finalizeBuzzHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = requireClassroom(request.get("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), request.get("questionId"));
      if (QuestionState.valueOf(question.state()) != QuestionState.LOCKED) {
        throw new IllegalArgumentException("Buzz review requires a stopped question");
      }

      int roundNumber = buzzRoundRepository.current(question.id());
      ClassroomScoringPolicy scoringPolicy = ClassroomScoringPolicy.fromSettings(
          classroom.participationSettings());
      BuzzAttemptService.FinalizationResult result = buzzAttemptService.finalizeRound(
          question.id(), roundNumber);

      String syncStatus = "NOT_REQUIRED";
      String syncError = "";
      if (result.winnerSubmissionId() != null) {
        SubmissionEntity winner = submissionRepository.findById(result.winnerSubmissionId());
        UserEntity winnerUser = winner == null ? null : userRepository.findById(winner.userId());
        if (winner == null || winnerUser == null) {
          throw new IllegalStateException("Finalized winner submission is unavailable");
        }
        SheetSyncOutcome syncOutcome = syncSubmissionToSheet(question, winnerUser, winner);
        syncStatus = syncOutcome.status();
        syncError = syncOutcome.error();
        if (result.newlyFinalized() && scoringPolicy.buzzBonus() != 0) {
          int runningTotal = scoreRepository.currentTotal(classroom.id(), winner.userId())
              + scoringPolicy.buzzBonus();
          scoreRepository.save(new ScoreRecordEntity(
              UUID.randomUUID(), classroom.id(), winner.userId(), question.id(), "BUZZ_BONUS",
              scoringPolicy.buzzBonus(), runningTotal, Instant.now()));
        }
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("questionId", question.id().toString());
      response.put("roundId", Integer.toString(roundNumber));
      response.put("outcomeStatus", result.outcomeStatus());
      response.put("winnerSubmissionId", result.winnerSubmissionId() == null
          ? "" : result.winnerSubmissionId().toString());
      response.put("winnerUserId", result.winnerUserId() == null ? "" : result.winnerUserId().toString());
      response.put("winnerGroupNumber", result.winnerGroupNumber() == null ? 0 : result.winnerGroupNumber());
      response.put("groupSuccessfulBuzzCount", result.groupSuccessfulBuzzCount());
      response.put("syncStatus", syncStatus);
      response.put("syncError", syncError);
      HttpResponses.json(exchange, 200, response);
    };
  }

  public HttpHandlerFunction listCurrentHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      ClassroomEntity classroom = requireClassroom(exchange.queryParam("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), exchange.queryParam("questionId"));
      UserEntity requester = requireUser(authContext.subject());
      if (!classroomRepository.hasMember(classroom.id(), requester.id())) {
        throw new SecurityException("User is not a member of this classroom");
      }

      int roundNumber = QuestionType.valueOf(question.type()) == QuestionType.BUZZ
          ? buzzRoundRepository.current(question.id()) : 0;
      List<Map<String, Object>> answers = submissionRepository.listByQuestionAndRound(question.id(), roundNumber)
          .stream()
          .map(submission -> {
            UserEntity user = userRepository.findById(submission.userId());
            Integer groupNumber = submission.groupNumber() != null
                ? submission.groupNumber()
                : SheetAnswerFormatter.extractGroupNumber(user == null ? null : user.displayName());
            return Map.<String, Object>of(
                "submissionId", submission.id().toString(),
                "questionId", submission.questionId().toString(),
                "userId", submission.userId().toString(),
                "isOwnSubmission", submission.userId().equals(requester.id()),
                "displayName", user == null ? submission.userId().toString() : user.displayName(),
                "answer", submission.submittedAnswer(),
                "reviewStatus", submission.reviewStatus() == null ? "" : submission.reviewStatus(),
                "groupNumber", groupNumber == null ? "" : groupNumber,
                "enteredByTeacher", submission.enteredByTeacher(),
                "groupChallenged", submission.groupChallenged()
            );
          })
          .toList();

      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "questionId", question.id().toString(),
          "answers", answers
      ));
    };
  }

  public HttpHandlerFunction reviewHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      UUID submissionId = UUID.fromString(request.getOrDefault("submissionId", ""));
      int awardedScore = Integer.parseInt(request.getOrDefault("awardedScore", "0"));
      SubmissionEntity submission = submissionRepository.findById(submissionId);
      if (submission == null) {
        throw new IllegalArgumentException("Submission not found");
      }

      SubmissionEntity reviewed = new SubmissionEntity(
          submission.id(),
          submission.classroomId(),
          submission.questionId(),
          submission.userId(),
          submission.attemptNumber(),
          submission.submittedAnswer(),
          submission.submissionStatus(),
          awardedScore > 0,
          awardedScore,
          "REVIEWED",
          submission.submittedAt(),
          Instant.now(),
          submission.groupNumber(),
          submission.enteredByTeacher(),
          submission.groupChallenged(),
          submission.buzzWinner()
      );
      submissionRepository.updateReview(reviewed);

      int runningTotal = scoreRepository.currentTotal(reviewed.classroomId(), reviewed.userId()) + awardedScore;
      scoreRepository.save(new ScoreRecordEntity(
          UUID.randomUUID(),
          reviewed.classroomId(),
          reviewed.userId(),
          reviewed.questionId(),
          "REVIEW_SCORE",
          awardedScore,
          runningTotal,
          Instant.now()
      ));

      ClassroomEntity classroom = classroomRepository.findById(reviewed.classroomId());
      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_REVIEWED", Map.of(
          "submissionId", reviewed.id().toString(),
          "questionId", reviewed.questionId().toString(),
          "userId", reviewed.userId().toString()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", reviewed.id().toString(),
          "reviewStatus", reviewed.reviewStatus(),
          "awardedScore", reviewed.awardedScore()
      ));
    };
  }

  public HttpHandlerFunction updateAnswerHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      UUID submissionId = UUID.fromString(request.getOrDefault("submissionId", ""));
      String answer = request.getOrDefault("answer", "");
      int groupNumber;
      try {
        groupNumber = Integer.parseInt(request.getOrDefault("groupNumber", "0"));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("groupNumber must be a positive integer");
      }
      if (groupNumber <= 0) throw new IllegalArgumentException("groupNumber must be a positive integer");
      SubmissionEntity submission = submissionRepository.findById(submissionId);
      if (submission == null) {
        throw new IllegalArgumentException("Submission not found");
      }

      SubmissionEntity updated = new SubmissionEntity(
          submission.id(),
          submission.classroomId(),
          submission.questionId(),
          submission.userId(),
          submission.attemptNumber(),
          answer,
          submission.submissionStatus(),
          submission.isCorrect(),
          submission.awardedScore(),
          submission.reviewStatus(),
          submission.submittedAt(),
          submission.reviewedAt(),
          groupNumber,
          submission.enteredByTeacher(),
          true,
          submission.buzzWinner()
      );
      QuestionEntity question = questionRepository.findById(submission.classroomId(), submission.questionId());
      UserEntity user = userRepository.findById(submission.userId());
      Integer originalGroupNumber = SheetAnswerFormatter.extractGroupNumber(
          user == null ? null : user.displayName());
      boolean eligibilityRestored = originalGroupNumber == null || originalGroupNumber != groupNumber;
      submissionRepository.updateChallengedAnswer(updated, eligibilityRestored);
      SheetSyncOutcome syncOutcome = syncSubmissionToSheet(question, user, updated);
      ClassroomEntity classroom = classroomRepository.findById(updated.classroomId());
      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_UPDATED", Map.of(
          "submissionId", updated.id().toString(),
          "questionId", updated.questionId().toString(),
          "userId", updated.userId().toString()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", updated.id().toString(),
          "answer", updated.submittedAnswer(),
          "groupNumber", updated.groupNumber(),
          "eligibilityRestored", eligibilityRestored,
          "syncStatus", syncOutcome.status(),
          "syncError", syncOutcome.error()
      ));
    };
  }

  public HttpHandlerFunction createManualAnswerHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = requireClassroom(request.get("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), request.get("questionId"));
      if (QuestionState.valueOf(question.state()) != QuestionState.LOCKED) {
        throw new IllegalArgumentException("Manual answers are allowed only after the question is stopped");
      }
      int groupNumber;
      try {
        groupNumber = Integer.parseInt(request.getOrDefault("groupNumber", "0"));
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("groupNumber must be a positive integer");
      }
      if (groupNumber <= 0) throw new IllegalArgumentException("groupNumber must be a positive integer");
      UserEntity actor = requireUser(authContext.subject());
      int roundNumber = QuestionType.valueOf(question.type()) == QuestionType.BUZZ
          ? buzzRoundRepository.current(question.id()) : 0;
      if (!submissionRepository.listByQuestionAndRound(question.id(), roundNumber).isEmpty()) {
        throw new IllegalArgumentException("A current answer already exists");
      }
      SubmissionEntity submission = new SubmissionEntity(
          UUID.randomUUID(), classroom.id(), question.id(), actor.id(), 1,
          request.getOrDefault("answer", ""), "RECEIVED", null, null,
          "PENDING_REVIEW", Instant.now(), null, groupNumber, true, true, false);
      submissionRepository.save(submission, roundNumber);
      syncSubmissionToSheet(question, actor, submission);
      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_CREATED", Map.of(
          "submissionId", submission.id().toString(),
          "questionId", question.id().toString(),
          "userId", actor.id().toString()
      ));
      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", submission.id().toString(),
          "answer", submission.submittedAnswer(),
          "groupNumber", submission.groupNumber()
      ));
    };
  }

  public HttpHandlerFunction retryAnswerSyncHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      UUID submissionId = UUID.fromString(request.getOrDefault("submissionId", ""));
      SubmissionEntity submission = submissionRepository.findById(submissionId);
      if (submission == null) throw new IllegalArgumentException("Submission not found");
      QuestionEntity question = questionRepository.findById(submission.classroomId(), submission.questionId());
      UserEntity user = userRepository.findById(submission.userId());
      SheetSyncOutcome outcome = syncSubmissionToSheet(question, user, submission);
      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", submission.id().toString(),
          "syncStatus", outcome.status(),
          "syncError", outcome.error()
      ));
    };
  }

  public HttpHandlerFunction deleteAnswerHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      UUID submissionId = UUID.fromString(request.getOrDefault("submissionId", ""));
      SubmissionEntity submission = submissionRepository.findById(submissionId);
      if (submission == null) {
        throw new IllegalArgumentException("Submission not found");
      }

      submissionRepository.delete(submissionId);
      if (submission.awardedScore() != null && submission.awardedScore() != 0) {
        int reversedScore = -submission.awardedScore();
        int runningTotal = scoreRepository.currentTotal(submission.classroomId(), submission.userId()) + reversedScore;
        scoreRepository.save(new ScoreRecordEntity(
            UUID.randomUUID(), submission.classroomId(), submission.userId(), submission.questionId(),
            "ANSWER_DELETED", reversedScore, runningTotal, Instant.now()
        ));
      }

      ClassroomEntity classroom = classroomRepository.findById(submission.classroomId());
      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_DELETED", Map.of(
          "submissionId", submission.id().toString(),
          "questionId", submission.questionId().toString(),
          "userId", submission.userId().toString()
      ));
      eventBroker.publish(classroom.classroomCode(), "QUESTION_STATISTICS_UPDATED", Map.of(
          "questionId", submission.questionId().toString(),
          "submissionCount", submissionRepository.listByQuestionId(submission.questionId()).size()
      ));
      HttpResponses.json(exchange, 200, Map.of("deleted", submissionId.toString()));
    };
  }

  private SubmissionDecision decide(ClassroomEntity classroom, QuestionEntity question, String answer) {
    QuestionType type = QuestionType.valueOf(question.type());
    return switch (type) {
      case SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE -> {
        boolean correct = question.answerKey().trim().equals(answer.trim());
        ClassroomScoringPolicy scoringPolicy = ClassroomScoringPolicy.fromSettings(classroom.participationSettings());
        int delta = correct ? question.score() : -Math.max(0, scoringPolicy.wrongAnswerPenalty());
        yield new SubmissionDecision(correct, delta, null);
      }
      case SHORT_ANSWER, BUZZ -> new SubmissionDecision(null, null, "PENDING_REVIEW");
    };
  }

  private ClassroomEntity requireClassroom(String classroomCode) {
    if (classroomCode == null || classroomCode.isBlank()) {
      throw new IllegalArgumentException("classroomCode is required");
    }
    ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
    if (classroom == null) {
      throw new IllegalArgumentException("Classroom not found");
    }
    return classroom;
  }

  private QuestionEntity requireQuestion(UUID classroomId, String questionIdValue) {
    if (questionIdValue == null || questionIdValue.isBlank()) {
      throw new IllegalArgumentException("questionId is required");
    }
    QuestionEntity question = questionRepository.findById(classroomId, UUID.fromString(questionIdValue));
    if (question == null) {
      throw new IllegalArgumentException("Question not found");
    }
    return question;
  }

  private UserEntity requireUser(String externalId) {
    UserEntity user = userRepository.findByExternalId(externalId);
    if (user == null) {
      throw new IllegalArgumentException("User not found");
    }
    return user;
  }

  private SheetSyncOutcome syncSubmissionToSheet(QuestionEntity question, UserEntity user, SubmissionEntity submission) {
    try {
      Map<String, String> settings = JsonMapParser.parseObject(question.publicationSettings());
      String sheetConfigId = settings.getOrDefault("sheetConfigId", "").replace("\"", "").trim();
      UUID persistedSheetConfigId = questionPackageService.findSheetConfigId(question.id());
      if (persistedSheetConfigId != null) sheetConfigId = persistedSheetConfigId.toString();
      String targetCell = settings.getOrDefault("targetCell", "").replace("\"", "").trim();
      if (sheetConfigId.isEmpty()) return new SheetSyncOutcome("NOT_REQUIRED", "");
      SheetConfigEntity config = sheetConfigRepository.findById(UUID.fromString(sheetConfigId));
      if (config == null) throw new IllegalArgumentException("Sheet configuration not found");
      List<QuestionItemEntity> items = questionItemRepository.listByQuestionId(question.id());
      boolean synchronizedToSheet;
      if (!items.isEmpty()) {
        Object parsed = JsonParser.parse(submission.submittedAnswer());
        if (!(parsed instanceof Map<?, ?> answers)) {
          throw new IllegalArgumentException("Submission does not contain package answers");
        }
        Map<String, String> valuesByCell = new LinkedHashMap<>();
        for (QuestionItemEntity item : items) {
          Object answer = answers.get(item.id().toString());
          if (item.targetCell() != null && answer != null) {
            valuesByCell.put(item.targetCell(), SheetAnswerFormatter.format(String.valueOf(answer), submission.groupNumber()));
          }
        }
        synchronizedToSheet = googleSheetsSyncService.updateCells(config.sheetId(), valuesByCell);
      } else if (!targetCell.isEmpty()) {
        synchronizedToSheet = googleSheetsSyncService.updateCell(config.sheetId(), targetCell,
            SheetAnswerFormatter.format(submission.submittedAnswer(), submission.groupNumber()));
      } else {
        return new SheetSyncOutcome("NOT_REQUIRED", "");
      }
      if (!synchronizedToSheet) {
        throw new IllegalStateException("Google Sheets credentials are not configured");
      }
      submissionRepository.updateSheetSync(submission.id(), "SYNCED", null);
      return new SheetSyncOutcome("SYNCED", "");
    } catch (RuntimeException exception) {
      String error = exception.getMessage() == null ? "Google Sheets synchronization failed" : exception.getMessage();
      submissionRepository.updateSheetSync(submission.id(), "RETRYABLE", error);
      return new SheetSyncOutcome("RETRYABLE", error);
    }
  }

  private record SubmissionDecision(Boolean isCorrect, Integer awardedScore, String reviewStatus) {
  }

  private record SheetSyncOutcome(String status, String error) {
  }
}
