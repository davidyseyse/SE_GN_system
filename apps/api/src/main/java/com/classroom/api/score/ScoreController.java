package com.classroom.api.score;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ScoreController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final UserRepository userRepository;
  private final ScoreRepository scoreRepository;

  public ScoreController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      UserRepository userRepository,
      ScoreRepository scoreRepository
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.userRepository = userRepository;
    this.scoreRepository = scoreRepository;
  }

  public HttpHandlerFunction detailHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      ClassroomEntity classroom = requireClassroom(exchange.queryParam("classroomCode"));
      UserEntity requester = requireUser(authContext.subject());
      UUID targetUserId = UUID.fromString(exchange.queryParam("userId"));
      if (authContext.role() == Role.STUDENT && !requester.id().equals(targetUserId)) {
        throw new IllegalArgumentException("Students cannot access another student's score detail");
      }

      List<Map<String, Object>> details = new ArrayList<>();
      for (ScoreRecordEntity record : scoreRepository.listByClassroom(classroom.id())) {
        if (!record.userId().equals(targetUserId)) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("questionId", record.questionId() == null ? "" : record.questionId().toString());
        row.put("sourceType", record.sourceType());
        row.put("deltaScore", record.deltaScore());
        row.put("runningTotal", record.runningTotal());
        row.put("createdAt", record.createdAt().toString());
        details.add(row);
      }

      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "userId", targetUserId.toString(),
          "details", details
      ));
    };
  }

  private ClassroomEntity requireClassroom(String classroomCode) {
    ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
    if (classroom == null) {
      throw new IllegalArgumentException("Classroom not found");
    }
    return classroom;
  }

  private UserEntity requireUser(String externalId) {
    UserEntity user = userRepository.findByExternalId(externalId);
    if (user == null) {
      throw new IllegalArgumentException("User not found");
    }
    return user;
  }
}
