package com.classroom.api.score;

import com.classroom.api.contract.LeaderboardVisibility;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.LeaderboardSnapshotEntity;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.LeaderboardSnapshotRepository;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.data.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LeaderboardService {
  private final ScoreRepository scoreRepository;
  private final UserRepository userRepository;
  private final LeaderboardSnapshotRepository leaderboardSnapshotRepository;

  public LeaderboardService(
      ScoreRepository scoreRepository,
      UserRepository userRepository,
      LeaderboardSnapshotRepository leaderboardSnapshotRepository
  ) {
    this.scoreRepository = scoreRepository;
    this.userRepository = userRepository;
    this.leaderboardSnapshotRepository = leaderboardSnapshotRepository;
  }

  public List<Map<String, Object>> leaderboard(ClassroomEntity classroom, boolean teacherView, UUID requesterUserId) {
    List<Map<String, Object>> rankings = calculateRankings(classroom);
    LeaderboardVisibility visibility = LeaderboardVisibility.valueOf(classroom.leaderboardVisibility());

    List<Map<String, Object>> visible = switch (visibility) {
      case HIDDEN -> teacherView ? rankings : rankings.stream()
          .filter(row -> requesterUserId != null && requesterUserId.toString().equals(row.get("userId")))
          .toList();
      case TOP_3 -> teacherView ? rankings : rankings.stream().limit(3).toList();
      case TOP_10 -> teacherView ? rankings : rankings.stream().limit(10).toList();
      case FULL -> rankings;
    };

    leaderboardSnapshotRepository.save(new LeaderboardSnapshotEntity(
        UUID.randomUUID(),
        classroom.id(),
        Instant.now(),
        serializeRankings(rankings),
        visibility.name()
    ));
    return visible;
  }

  public List<Map<String, Object>> calculateRankings(ClassroomEntity classroom) {
    Map<UUID, Integer> totals = new LinkedHashMap<>();
    for (ScoreRecordEntity record : scoreRepository.listByClassroom(classroom.id())) {
      totals.put(record.userId(), Math.max(totals.getOrDefault(record.userId(), Integer.MIN_VALUE), record.runningTotal()));
    }

    List<Map<String, Object>> rankings = new ArrayList<>();
    totals.entrySet().stream()
        .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
        .forEachOrdered(entry -> {
          UserEntity user = userRepository.findById(entry.getKey());
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("userId", entry.getKey().toString());
          row.put("displayName", user == null ? "Unknown" : user.displayName());
          row.put("score", entry.getValue());
          rankings.add(row);
        });

    for (int index = 0; index < rankings.size(); index++) {
      rankings.get(index).put("rank", index + 1);
    }
    return rankings;
  }

  private String serializeRankings(List<Map<String, Object>> rankings) {
    StringBuilder builder = new StringBuilder("[");
    for (int index = 0; index < rankings.size(); index++) {
      Map<String, Object> row = rankings.get(index);
      builder.append("{\"rank\":").append(row.get("rank"))
          .append(",\"userId\":\"").append(row.get("userId")).append("\"")
          .append(",\"displayName\":\"").append(row.get("displayName")).append("\"")
          .append(",\"score\":").append(row.get("score")).append("}");
      if (index + 1 < rankings.size()) {
        builder.append(",");
      }
    }
    builder.append("]");
    return builder.toString();
  }
}
