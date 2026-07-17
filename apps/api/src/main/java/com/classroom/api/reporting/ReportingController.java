package com.classroom.api.reporting;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.AuditLogEntity;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.AuditLogRepository;
import com.classroom.api.data.repository.BuzzRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportingController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final QuestionRepository questionRepository;
  private final SubmissionRepository submissionRepository;
  private final BuzzRepository buzzRepository;
  private final ScoreRepository scoreRepository;
  private final UserRepository userRepository;
  private final AuditLogRepository auditLogRepository;

  public ReportingController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      SubmissionRepository submissionRepository,
      BuzzRepository buzzRepository,
      ScoreRepository scoreRepository,
      UserRepository userRepository,
      AuditLogRepository auditLogRepository
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.submissionRepository = submissionRepository;
    this.buzzRepository = buzzRepository;
    this.scoreRepository = scoreRepository;
    this.userRepository = userRepository;
    this.auditLogRepository = auditLogRepository;
  }

  public HttpHandlerFunction summaryHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      ClassroomEntity classroom = requireClassroom(exchange.queryParam("classroomCode"));
      List<QuestionEntity> questions = questionRepository.listByClassroom(classroom.id());
      int submissionCount = questions.stream().mapToInt(question -> submissionRepository.listByQuestionId(question.id()).size()).sum();
      int buzzCount = questions.stream().mapToInt(question -> buzzRepository.findByQuestionId(question.id()).size()).sum();
      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "participantCount", classroomRepository.listMembers(classroom.id()).size(),
          "questionCount", questions.size(),
          "submissionCount", submissionCount,
          "buzzCount", buzzCount,
          "scoreRecordCount", scoreRepository.listByClassroom(classroom.id()).size()
      ));
    };
  }

  public HttpHandlerFunction questionStatsHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      ClassroomEntity classroom = requireClassroom(exchange.queryParam("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), exchange.queryParam("questionId"));
      List<SubmissionEntity> submissions = submissionRepository.listByQuestionId(question.id());
      long correct = submissions.stream().filter(submission -> Boolean.TRUE.equals(submission.isCorrect())).count();
      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "questionId", question.id().toString(),
          "state", question.state(),
          "submissionCount", submissions.size(),
          "correctCount", correct,
          "correctRate", submissions.isEmpty() ? 0.0 : ((double) correct / submissions.size())
      ));
    };
  }

  public HttpHandlerFunction exportHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      ClassroomEntity classroom = requireClassroom(exchange.queryParam("classroomCode"));
      String exportType = exchange.queryParam("type");
      UserEntity actor = requireUser(authContext.subject());
      String csv = buildCsv(classroom, exportType == null ? "scores" : exportType);

      auditLogRepository.save(new AuditLogEntity(
          UUID.randomUUID(),
          classroom.id(),
          actor.id(),
          "EXPORT_GENERATED",
          "CLASSROOM",
          classroom.id(),
          "{\"type\":\"" + (exportType == null ? "scores" : exportType) + "\"}",
          Instant.now()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "type", exportType == null ? "scores" : exportType,
          "content", csv
      ));
    };
  }

  private String buildCsv(ClassroomEntity classroom, String type) {
    return switch (type) {
      case "buzz" -> {
        StringBuilder builder = new StringBuilder("questionId,userId,requestId,receiptOrder,outcomeStatus\n");
        for (QuestionEntity question : questionRepository.listByClassroom(classroom.id())) {
          for (BuzzRecordEntity record : buzzRepository.findByQuestionId(question.id())) {
            builder.append(question.id()).append(",")
                .append(record.userId()).append(",")
                .append(record.requestId()).append(",")
                .append(record.receiptOrder()).append(",")
                .append(record.outcomeStatus()).append("\n");
          }
        }
        yield builder.toString();
      }
      case "submissions" -> {
        StringBuilder builder = new StringBuilder("questionId,userId,submittedAnswer,isCorrect,awardedScore,reviewStatus\n");
        for (QuestionEntity question : questionRepository.listByClassroom(classroom.id())) {
          for (SubmissionEntity submission : submissionRepository.listByQuestionId(question.id())) {
            builder.append(question.id()).append(",")
                .append(submission.userId()).append(",")
                .append(submission.submittedAnswer().replace(",", " ")).append(",")
                .append(submission.isCorrect()).append(",")
                .append(submission.awardedScore()).append(",")
                .append(submission.reviewStatus()).append("\n");
          }
        }
        yield builder.toString();
      }
      default -> {
        StringBuilder builder = new StringBuilder("userId,displayName,sourceType,deltaScore,runningTotal\n");
        for (ScoreRecordEntity record : scoreRepository.listByClassroom(classroom.id())) {
          UserEntity user = userRepository.findById(record.userId());
          builder.append(record.userId()).append(",")
              .append(user == null ? "Unknown" : user.displayName().replace(",", " ")).append(",")
              .append(record.sourceType()).append(",")
              .append(record.deltaScore()).append(",")
              .append(record.runningTotal()).append("\n");
        }
        yield builder.toString();
      }
    };
  }

  private ClassroomEntity requireClassroom(String classroomCode) {
    ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
    if (classroom == null) {
      throw new IllegalArgumentException("Classroom not found");
    }
    return classroom;
  }

  private QuestionEntity requireQuestion(UUID classroomId, String questionIdValue) {
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
}
