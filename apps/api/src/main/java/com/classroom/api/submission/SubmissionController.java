package com.classroom.api.submission;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.QuestionState;
import com.classroom.api.contract.QuestionType;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.model.SheetConfigEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.repository.BuzzRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.score.ClassroomScoringPolicy;
import com.classroom.api.sheets.GoogleSheetsSyncService;
import com.classroom.api.util.JsonMapParser;
import java.time.Instant;
import java.util.Comparator;
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
  private final BuzzRepository buzzRepository;
  private final RealtimeEventBroker eventBroker;
  private final GoogleSheetsSyncService googleSheetsSyncService;

  public SubmissionController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      SubmissionRepository submissionRepository,
      ScoreRepository scoreRepository,
      SheetConfigRepository sheetConfigRepository,
      UserRepository userRepository,
      BuzzRepository buzzRepository,
      RealtimeEventBroker eventBroker,
      GoogleSheetsSyncService googleSheetsSyncService
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.submissionRepository = submissionRepository;
    this.scoreRepository = scoreRepository;
    this.sheetConfigRepository = sheetConfigRepository;
    this.userRepository = userRepository;
    this.buzzRepository = buzzRepository;
    this.eventBroker = eventBroker;
    this.googleSheetsSyncService = googleSheetsSyncService;
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
      assertUserCanSubmit(question, user, state);
      if (submissionRepository.hasSubmission(question.id(), user.id())) {
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
          decision.reviewStatus() == null ? now : null
      );
      submissionRepository.save(submission);
      syncSubmissionToSheet(question, user, submission);

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

      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_RECEIVED", Map.of(
          "questionId", question.id().toString(),
          "userId", user.id().toString(),
          "status", submission.submissionStatus(),
          "reviewStatus", decision.reviewStatus() == null ? "" : decision.reviewStatus()
      ));
      eventBroker.publish(classroom.classroomCode(), "QUESTION_STATISTICS_UPDATED", Map.of(
          "questionId", question.id().toString(),
          "submissionCount", submissionRepository.listByQuestionId(question.id()).size()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", submission.id().toString(),
          "isCorrect", decision.isCorrect() == null ? "" : decision.isCorrect().toString(),
          "awardedScore", decision.awardedScore() == null ? "" : decision.awardedScore().toString(),
          "reviewStatus", decision.reviewStatus() == null ? "" : decision.reviewStatus()
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
          Instant.now()
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
      eventBroker.publish(classroom.classroomCode(), "LEADERBOARD_UPDATED", Map.of(
          "questionId", reviewed.questionId().toString(),
          "userId", reviewed.userId().toString(),
          "deltaScore", awardedScore
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
          submission.reviewedAt()
      );
      submissionRepository.updateAnswer(updated);

      QuestionEntity question = questionRepository.findById(submission.classroomId(), submission.questionId());
      UserEntity user = userRepository.findById(submission.userId());
      syncSubmissionToSheet(question, user, updated);

      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", updated.id().toString(),
          "answer", updated.submittedAnswer()
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

  private void assertUserCanSubmit(QuestionEntity question, UserEntity user, QuestionState state) {
    if (state != QuestionState.BUZZ_OPEN || QuestionType.valueOf(question.type()) != QuestionType.BUZZ) {
      return;
    }

    BuzzRecordEntity winner = buzzRepository.findByQuestionId(question.id()).stream()
        .filter(BuzzRecordEntity::successful)
        .max(Comparator.comparingLong(BuzzRecordEntity::receiptOrder))
        .orElseThrow(() -> new IllegalArgumentException("No buzz winner yet"));
    if (!winner.userId().equals(user.id())) {
      throw new SecurityException("Only the buzz winner can submit an answer right now");
    }
  }

  private void syncSubmissionToSheet(QuestionEntity question, UserEntity user, SubmissionEntity submission) {
    Map<String, String> settings = JsonMapParser.parseObject(question.publicationSettings());
    String sheetConfigId = settings.getOrDefault("sheetConfigId", "").replace("\"", "").trim();
    String targetCell = settings.getOrDefault("targetCell", "").replace("\"", "").trim();
    if (sheetConfigId.isEmpty() || targetCell.isEmpty()) {
      return;
    }

    SheetConfigEntity config = sheetConfigRepository.findById(UUID.fromString(sheetConfigId));
    if (config == null) {
      return;
    }

    String answerValue = submission.submittedAnswer();
    String syncValue = user.displayName() + "：" + answerValue;
    googleSheetsSyncService.updateCell(config.sheetId(), targetCell, syncValue);
  }

  private record SubmissionDecision(Boolean isCorrect, Integer awardedScore, String reviewStatus) {
  }
}
