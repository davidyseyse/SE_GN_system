package com.classroom.api.question;

import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.QuestionState;
import com.classroom.api.contract.QuestionType;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.util.JsonMapParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class QuestionBatchController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final QuestionRepository questionRepository;
  private final QuestionLifecycleService lifecycleService;

  public QuestionBatchController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      QuestionLifecycleService lifecycleService
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.lifecycleService = lifecycleService;
  }

  public HttpHandlerFunction batchHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = classroomRepository.findByCode(request.getOrDefault("classroomCode", ""));
      if (classroom == null) {
        throw new IllegalArgumentException("Classroom not found");
      }
      String action = request.getOrDefault("action", "").toUpperCase();
      if (!List.of("OPEN_ALL", "STOP_ALL", "RESET_ALL").contains(action)) {
        throw new IllegalArgumentException("Unsupported batch action");
      }

      List<Map<String, Object>> results = new ArrayList<>();
      for (QuestionEntity question : questionRepository.listByClassroom(classroom.id())) {
        if (QuestionType.valueOf(question.type()) != QuestionType.BUZZ) {
          results.add(skipped(question, "Question is not buzz-enabled").toMap());
          continue;
        }
        try {
          results.add(apply(action, classroom, question).toMap());
        } catch (Exception exception) {
          results.add(new QuestionLifecycleService.OperationResult(
              question.id().toString(),
              question.state(),
              question.state(),
              "FAILED",
              lifecycleService.currentRound(question),
              exception.getMessage() == null ? "Batch item failed" : exception.getMessage()
          ).toMap());
        }
      }

      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "action", action,
          "results", results
      ));
    };
  }

  private QuestionLifecycleService.OperationResult apply(
      String action,
      ClassroomEntity classroom,
      QuestionEntity question
  ) {
    QuestionState state = QuestionState.valueOf(question.state());
    return switch (action) {
      case "OPEN_ALL" -> state == QuestionState.READY
          ? lifecycleService.transition(classroom, question, QuestionState.BUZZ_OPEN)
          : skipped(question, state == QuestionState.BUZZ_OPEN
              ? "Question is already open" : "Question is not eligible to open");
      case "STOP_ALL" -> state == QuestionState.BUZZ_OPEN || state == QuestionState.ANSWER_OPEN
          ? lifecycleService.transition(classroom, question, QuestionState.LOCKED)
          : skipped(question, "Question is not active");
      case "RESET_ALL" -> lifecycleService.reset(classroom, question);
      default -> throw new IllegalArgumentException("Unsupported batch action");
    };
  }

  private QuestionLifecycleService.OperationResult skipped(QuestionEntity question, String reason) {
    return new QuestionLifecycleService.OperationResult(
        question.id().toString(),
        question.state(),
        question.state(),
        "SKIPPED",
        lifecycleService.currentRound(question),
        reason
    );
  }
}
