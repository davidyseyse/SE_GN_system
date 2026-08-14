package com.classroom.api.classroom;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.ClassroomMemberEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.score.ClassroomScoringPolicy;
import com.classroom.api.sheets.SheetAnswerFormatter;
import com.classroom.api.util.JsonMapParser;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClassroomController {
  private final ClassroomRepository classroomRepository;
  private final UserRepository userRepository;
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomCodeGenerator classroomCodeGenerator;

  public ClassroomController(
      ClassroomRepository classroomRepository,
      UserRepository userRepository,
      AuthorizationGuard authorizationGuard
  ) {
    this.classroomRepository = classroomRepository;
    this.userRepository = userRepository;
    this.authorizationGuard = authorizationGuard;
    this.classroomCodeGenerator = new ClassroomCodeGenerator();
  }

  public HttpHandlerFunction createClassroomHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      String name = requiredValue(request, "name");
      String participationSettings = request.getOrDefault("participationSettings", "{}");
      ClassroomScoringPolicy.validateSettings(participationSettings);

      UserEntity actor = upsertActor(authContext);
      Instant now = Instant.now();
      ClassroomEntity classroom = new ClassroomEntity(
          UUID.randomUUID(),
          actor.id(),
          classroomCodeGenerator.generate(),
          name,
          "DRAFT",
          participationSettings,
          null,
          now,
          now,
          null,
          null
      );
      classroomRepository.save(classroom);
      classroomRepository.upsertMemberPresence(new ClassroomMemberEntity(
          UUID.randomUUID(),
          classroom.id(),
          actor.id(),
          authContext.role().name(),
          "ONLINE",
          now,
          now,
          0
      ));

      HttpResponses.json(exchange, 201, Map.of(
          "classroom", Map.of(
              "id", classroom.id().toString(),
              "code", classroom.classroomCode(),
              "name", classroom.name(),
              "status", classroom.status()
          )
      ));
    };
  }

  public HttpHandlerFunction listClassroomsHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      UserEntity actor = upsertActor(authContext);
      HttpResponses.json(exchange, 200, Map.of(
          "classrooms", classroomRepository.listByTeacherId(actor.id()).stream()
              .map(ClassroomController::classroomView)
              .toList()
      ));
    };
  }

  public HttpHandlerFunction availableClassroomsHandler() {
    return exchange -> HttpResponses.json(exchange, 200, Map.of(
        "classrooms", classroomRepository.listAll().stream()
            .map(ClassroomController::classroomView)
            .toList()
    ));
  }

  public HttpHandlerFunction deleteClassroomHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      String classroomCode = requiredValue(request, "classroomCode").toUpperCase();
      ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
      if (classroom == null) {
        throw new IllegalArgumentException("Classroom not found");
      }

      UserEntity actor = upsertActor(authContext);
      if (!classroom.teacherId().equals(actor.id())) {
        throw new SecurityException("Only the classroom owner can delete this classroom");
      }
      if (!classroomRepository.deleteOwnedClassroom(classroom.id(), actor.id())) {
        throw new IllegalArgumentException("Classroom not found");
      }

      HttpResponses.json(exchange, 200, Map.of(
          "deletedClassroomCode", classroom.classroomCode()
      ));
    };
  }

  public HttpHandlerFunction joinClassroomHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.STUDENT, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      String classroomCode = requiredValue(request, "classroomCode");

      ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
      if (classroom == null) {
        throw new IllegalArgumentException("Classroom not found");
      }

      UserEntity actor = upsertActor(authContext);
      Instant now = Instant.now();
      classroomRepository.upsertMemberPresence(new ClassroomMemberEntity(
          UUID.randomUUID(),
          classroom.id(),
          actor.id(),
          authContext.role().name(),
          "ONLINE",
          now,
          now,
          0
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "classroom", Map.of(
              "id", classroom.id().toString(),
              "code", classroom.classroomCode(),
              "name", classroom.name(),
              "status", classroom.status()
          ),
          "participant", Map.of(
              "id", actor.id().toString(),
              "displayName", actor.displayName(),
              "role", actor.role()
          )
      ));
    };
  }

  public HttpHandlerFunction membersHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      String classroomCode = exchange.queryParam("classroomCode");
      if (classroomCode == null || classroomCode.isBlank()) {
        throw new IllegalArgumentException("classroomCode query parameter is required");
      }

      ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
      if (classroom == null) {
        throw new IllegalArgumentException("Classroom not found");
      }

      List<ClassroomMemberEntity> members = classroomRepository.listMembers(classroom.id());
      int successfulBuzzLimit = ClassroomScoringPolicy.fromSettings(
          classroom.participationSettings()).successfulBuzzLimit();
      HttpResponses.json(exchange, 200, Map.of(
          "classroomId", classroom.id().toString(),
          "classroomCode", classroom.classroomCode(),
          "successfulBuzzLimit", successfulBuzzLimit,
          "members", members.stream().map(member -> {
            UserEntity user = userRepository.findById(member.userId());
            String displayName = user == null ? "" : user.displayName();
            Integer groupNumber = SheetAnswerFormatter.extractGroupNumber(displayName);
            return Map.<String, Object>of(
                "id", member.id().toString(),
                "userId", member.userId().toString(),
                "displayName", displayName,
                "role", member.role(),
                "presenceStatus", member.presenceStatus(),
                "joinedAt", member.joinedAt().toString(),
                "lastSeenAt", member.lastSeenAt().toString(),
                "groupNumber", groupNumber == null ? 0 : groupNumber,
                "groupSuccessfulBuzzCount", groupNumber == null
                    ? 0 : classroomRepository.globalGroupSuccessfulBuzzCount(groupNumber)
            );
          }).toList()
      ));
    };
  }

  public HttpHandlerFunction resetAnswerLimitsHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      String classroomCode = requiredValue(request, "classroomCode");
      if (classroomRepository.findByCode(classroomCode) == null) {
        throw new IllegalArgumentException("Classroom not found");
      }

      int resetGroupCount = classroomRepository.resetCurrentTaipeiDayGlobalGroupSuccessfulBuzzCounts();
      HttpResponses.json(exchange, 200, Map.of(
          "status", "ANSWER_LIMITS_RESET",
          "resetGroupCount", resetGroupCount
      ));
    };
  }

  public HttpHandlerFunction presenceHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      String classroomCode = exchange.queryParam("classroomCode");
      if (classroomCode == null || classroomCode.isBlank()) {
        throw new IllegalArgumentException("classroomCode query parameter is required");
      }

      ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
      if (classroom == null) {
        throw new IllegalArgumentException("Classroom not found");
      }

      List<ClassroomMemberEntity> members = classroomRepository.listMembers(classroom.id());
      long onlineCount = members.stream().filter(member -> "ONLINE".equalsIgnoreCase(member.presenceStatus())).count();

      HttpResponses.json(exchange, 200, Map.of(
          "classroomId", classroom.id().toString(),
          "classroomCode", classroom.classroomCode(),
          "onlineCount", onlineCount,
          "participants", members.stream().map(member -> Map.of(
              "userId", member.userId().toString(),
              "role", member.role(),
              "presenceStatus", member.presenceStatus(),
              "lastSeenAt", member.lastSeenAt().toString()
          )).toList()
      ));
    };
  }

  private UserEntity upsertActor(AuthContext authContext) {
    UserEntity existing = userRepository.findByExternalId(authContext.subject());
    if (existing != null) {
      return existing;
    }

    UserEntity user = new UserEntity(
        UUID.randomUUID(),
        authContext.subject(),
        authContext.displayName(),
        authContext.role().name(),
        Instant.now(),
        Instant.now()
    );
    userRepository.save(user);
    return user;
  }

  private static Map<String, Object> classroomView(ClassroomEntity classroom) {
    return Map.of(
        "id", classroom.id().toString(),
        "code", classroom.classroomCode(),
        "name", classroom.name(),
        "status", classroom.status(),
        "createdAt", classroom.createdAt().toString()
    );
  }

  private static Map<String, String> parseBody(HttpExchangeAdapter exchange) throws IOException {
    return JsonMapParser.parseObject(exchange.readRequestBody());
  }

  private static String requiredValue(Map<String, String> values, String key) {
    String value = values.getOrDefault(key, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }
}
