package com.classroom.api.http;

import com.classroom.api.auth.AuthController;
import com.classroom.api.auth.AuthTokenService;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.buzz.BuzzController;
import com.classroom.api.classroom.ClassroomController;
import com.classroom.api.config.ServerConfig;
import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.repository.AuditLogRepository;
import com.classroom.api.data.repository.BuzzRepository;
import com.classroom.api.data.repository.BuzzRoundRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.CurrentBuzzStatusRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.question.QuestionController;
import com.classroom.api.question.QuestionBatchController;
import com.classroom.api.question.QuestionLifecycleService;
import com.classroom.api.question.QuestionPackageController;
import com.classroom.api.question.QuestionPackageService;
import com.classroom.api.redis.RedisBuzzLockService;
import com.classroom.api.reporting.ReportingController;
import com.classroom.api.realtime.RealtimeController;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.realtime.RealtimeSessionController;
import com.classroom.api.realtime.ws.WebSocketServer;
import com.classroom.api.score.ScoreController;
import com.classroom.api.sheets.GoogleSheetsSyncService;
import com.classroom.api.sheets.SheetConfigController;
import com.classroom.api.student.StudentController;
import com.classroom.api.submission.SubmissionController;
import com.classroom.api.submission.PackageSubmissionController;
import com.classroom.api.system.SystemController;
import com.classroom.api.teacher.TeacherController;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ApiServer {
  private final ServerConfig config;
  private final HttpServer server;
  private final Router router;
  private final WebSocketServer webSocketServer;

  public ApiServer(ServerConfig config) throws IOException {
    this.config = config;
    this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
    RouterContext routerContext = buildRouter(config);
    this.router = routerContext.router();
    this.webSocketServer = routerContext.webSocketServer();
    this.server.createContext("/", new RootHandler(config, this.router));
    this.server.setExecutor(Executors.newCachedThreadPool());
  }

  public void start() throws IOException {
    server.start();
    webSocketServer.start();
  }

  private static final class RootHandler implements HttpHandler {
    private final ServerConfig config;
    private final Router router;

    private RootHandler(ServerConfig config, Router router) {
      this.config = config;
      this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      addCorsHeaders(exchange, config);

      if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return;
      }

      HttpExchangeAdapter adapter = new HttpExchangeAdapter(exchange);
      RouteDefinition route = router.match(adapter.method(), adapter.path());
      if (route != null) {
        try {
          route.handler().handle(adapter);
        } catch (SecurityException exception) {
          HttpResponses.json(exchange, 403, Map.of(
              "error", exception.getMessage()
          ));
        } catch (IllegalArgumentException exception) {
          HttpResponses.json(exchange, 400, Map.of(
              "error", exception.getMessage()
          ));
        } catch (DatabaseException exception) {
          exception.printStackTrace();
          HttpResponses.json(exchange, 409, Map.of(
              "error", exception.getMessage()
          ));
        } catch (Exception exception) {
          exception.printStackTrace();
          HttpResponses.json(exchange, 500, Map.of(
              "error", "Internal server error"
          ));
        }
        return;
      }

      HttpResponses.json(exchange, 404, Map.of(
          "error", "Not Found",
          "path", adapter.path(),
          "method", adapter.method()
      ));
    }

    private static void addCorsHeaders(HttpExchange exchange, ServerConfig config) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.corsOrigin());
      exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
      exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
  }

  private static RouterContext buildRouter(ServerConfig config) {
    Router router = new Router();
    SystemController systemController = new SystemController(config);
    TeacherController teacherController = new TeacherController();
    StudentController studentController = new StudentController();
    AuthController authController = new AuthController(config);
    Database database = new Database(config);
    ClassroomRepository classroomRepository = new ClassroomRepository(database);
    UserRepository userRepository = new UserRepository(database);
    QuestionRepository questionRepository = new QuestionRepository(database);
    QuestionItemRepository questionItemRepository = new QuestionItemRepository(database);
    BuzzRepository buzzRepository = new BuzzRepository(database);
    BuzzRoundRepository buzzRoundRepository = new BuzzRoundRepository(database);
    CurrentBuzzStatusRepository currentBuzzStatusRepository = new CurrentBuzzStatusRepository(database);
    SubmissionRepository submissionRepository = new SubmissionRepository(database);
    ScoreRepository scoreRepository = new ScoreRepository(database);
    SheetConfigRepository sheetConfigRepository = new SheetConfigRepository(database);
    AuditLogRepository auditLogRepository = new AuditLogRepository(database);
    AuthTokenService authTokenService = authController.authTokenService();
    AuthorizationGuard authorizationGuard = new AuthorizationGuard(authTokenService);
    RealtimeEventBroker eventBroker = new RealtimeEventBroker();
    ClassroomController classroomController = new ClassroomController(classroomRepository, userRepository, authorizationGuard);
    RealtimeController realtimeController = new RealtimeController(config, eventBroker);
    RealtimeSessionController realtimeSessionController = new RealtimeSessionController(authorizationGuard, eventBroker);
    RedisBuzzLockService redisBuzzLockService = new RedisBuzzLockService(config);
    QuestionLifecycleService questionLifecycleService =
        new QuestionLifecycleService(database, questionRepository, redisBuzzLockService, eventBroker);
    QuestionPackageService questionPackageService =
        new QuestionPackageService(database, questionItemRepository, sheetConfigRepository);
    QuestionController questionController = new QuestionController(
        questionRepository, classroomRepository, userRepository, authorizationGuard, eventBroker,
        buzzRoundRepository, currentBuzzStatusRepository, questionLifecycleService,
        questionItemRepository, questionPackageService);
    QuestionBatchController questionBatchController =
        new QuestionBatchController(authorizationGuard, classroomRepository, questionRepository, questionLifecycleService);
    QuestionPackageController questionPackageController = new QuestionPackageController(
        authorizationGuard, classroomRepository, userRepository, questionRepository,
        questionItemRepository, questionPackageService);
    GoogleSheetsSyncService googleSheetsSyncService = new GoogleSheetsSyncService(config);
    SheetConfigController sheetConfigController = new SheetConfigController(authorizationGuard, sheetConfigRepository);
    BuzzController buzzController = new BuzzController(
        authorizationGuard, classroomRepository, questionRepository, userRepository, buzzRepository,
        scoreRepository, redisBuzzLockService, eventBroker, buzzRoundRepository, submissionRepository,
        questionLifecycleService, questionItemRepository);
    SubmissionController submissionController = new SubmissionController(authorizationGuard, classroomRepository, questionRepository, submissionRepository, scoreRepository, sheetConfigRepository, userRepository, buzzRepository, eventBroker, googleSheetsSyncService, buzzRoundRepository);
    PackageSubmissionController packageSubmissionController = new PackageSubmissionController(
        authorizationGuard, classroomRepository, questionRepository, questionItemRepository,
        submissionRepository, userRepository, buzzRepository, buzzRoundRepository,
        sheetConfigRepository, questionPackageService, googleSheetsSyncService, eventBroker);
    ScoreController scoreController = new ScoreController(authorizationGuard, classroomRepository, userRepository, scoreRepository);
    ReportingController reportingController = new ReportingController(authorizationGuard, classroomRepository, questionRepository, submissionRepository, buzzRepository, scoreRepository, userRepository, auditLogRepository);
    WebSocketServer webSocketServer = new WebSocketServer(config, authTokenService, eventBroker);

    router.add("GET", "/", systemController.rootHandler());
    router.add("GET", "/health", systemController.healthHandler());
    router.add("GET", "/api/health", systemController.healthHandler());
    router.add("GET", "/api/modules/teacher", teacherController.moduleInfoHandler());
    router.add("GET", "/api/modules/student", studentController.moduleInfoHandler());
    router.add("GET", "/api/realtime/config", realtimeController.configHandler());
    router.add("POST", "/api/realtime/session", realtimeSessionController.openSessionHandler());
    router.add("GET", "/api/realtime/resync", realtimeSessionController.resyncHandler());
    router.add("GET", "/api/realtime/events", realtimeSessionController.eventsHandler());
    router.add("POST", "/api/auth/token", authController.issueTokenHandler());
    router.add("POST", "/api/auth/admin-login", authController.adminLoginHandler());
    router.add("POST", "/api/auth/student-login", authController.studentLoginHandler());
    router.add("POST", "/api/classrooms", classroomController.createClassroomHandler());
    router.add("POST", "/api/classrooms/join", classroomController.joinClassroomHandler());
    router.add("GET", "/api/sheet-configs", sheetConfigController.listHandler());
    router.add("POST", "/api/sheet-configs", sheetConfigController.createHandler());
    router.add("POST", "/api/sheet-configs/delete", sheetConfigController.deleteHandler());
    router.add("GET", "/api/classrooms/members", classroomController.membersHandler());
    router.add("GET", "/api/classrooms/presence", classroomController.presenceHandler());
    router.add("POST", "/api/questions", questionController.createQuestionHandler());
    router.add("POST", "/api/question-packages", questionPackageController.createHandler());
    router.add("GET", "/api/question-packages", questionPackageController.listHandler());
    router.add("PUT", "/api/questions", questionController.updateQuestionHandler());
    router.add("POST", "/api/questions/archive", questionController.archiveQuestionHandler());
    router.add("POST", "/api/questions/delete", questionController.deleteQuestionHandler());
    router.add("POST", "/api/questions/reorder", questionController.reorderQuestionsHandler());
    router.add("GET", "/api/questions", questionController.listQuestionsHandler());
    router.add("POST", "/api/questions/state", questionController.transitionStateHandler());
    router.add("POST", "/api/questions/batch", questionBatchController.batchHandler());
    router.add("POST", "/api/buzz", buzzController.buzzHandler());
    router.add("POST", "/api/buzz/cancel", buzzController.cancelHandler());
    router.add("POST", "/api/buzz/reset", buzzController.resetHandler());
    router.add("GET", "/api/buzz/history", buzzController.historyHandler());
    router.add("POST", "/api/submissions", submissionController.submitHandler());
    router.add("POST", "/api/question-packages/answers", packageSubmissionController.submitHandler());
    router.add("POST", "/api/question-packages/answers/retry", packageSubmissionController.retrySyncHandler());
    router.add("GET", "/api/submissions", submissionController.listCurrentHandler());
    router.add("POST", "/api/submissions/answer", submissionController.updateAnswerHandler());
    router.add("POST", "/api/submissions/delete", submissionController.deleteAnswerHandler());
    router.add("POST", "/api/submissions/review", submissionController.reviewHandler());
    router.add("GET", "/api/scores/detail", scoreController.detailHandler());
    router.add("GET", "/api/reports/summary", reportingController.summaryHandler());
    router.add("GET", "/api/reports/question", reportingController.questionStatsHandler());
    router.add("GET", "/api/reports/submissions", reportingController.submissionsHandler());
    router.add("GET", "/api/reports/export", reportingController.exportHandler());
    return new RouterContext(router, webSocketServer);
  }

  private record RouterContext(Router router, WebSocketServer webSocketServer) {
  }
}
