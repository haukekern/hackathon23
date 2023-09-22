package de.hk;

import com.google.gson.Gson;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

public class Main {

  //  public static final String SECRET = "d20d723f-cad9-418f-ab9f-8107018a1cb7"; //Das Secret des Bot
  public static final String SECRET = "da5e5970-2583-4ac8-b9db-f80df268bcdd"; //Das Secret des Bot
  public static final String GAMESERVER = "https://games.uhno.de"; //URL zum Gameserver

  public static final Gson gson = new Gson();


  private static final List<List<Integer>> winnings = List.of(
      List.of(0, 4, 8),
      List.of(2, 4, 6),
      List.of(0, 1, 2),
      List.of(6, 7, 8),
      List.of(0, 3, 6),
      List.of(2, 5, 8),
      List.of(1, 4, 7),
      List.of(3, 4, 5)
  );

  //  private static final List<Integer> all = List.of(4, 0, 2, 6, 8, 1, 3, 5, 7);
  private static final List<Integer> all = List.of(7, 5, 3, 1, 8, 6, 2, 0, 4);

  public static void main(String... args) {
    URI uri = URI.create(GAMESERVER);
    IO.Options options = IO.Options.builder()
        .setTransports(new String[]{WebSocket.NAME})
        .build();

    Socket socket = IO.socket(uri, options);

    socket.on("connect", (event) -> {
      System.out.println("connect");

      socket.emit("authenticate", new Object[]{SECRET}, (response) -> {
        Boolean success = (Boolean) response[0];
        System.out.println("success: " + success);
      });
    });

    socket.on("disconnect", (event) -> {
      System.out.println("disconnect");
    });

    socket.on("data", (data) -> {
      String json = String.valueOf(data[0]);
//      System.out.println("json: " + json);

      Game game = gson.fromJson(json, Game.class);
      Player me = getMe(game);
      Player enemy = getEnemy(game);

      if (game.type == GameType.INIT) {
//        System.out.println("NEW GAME: " + game.id);
      } else if (game.type == GameType.ROUND) {
        Symbol[] overview = overviewToBoard(game.overview);
        Context context = new Context(
            overview,
            me, enemy,
            getNextWinSteps(me.symbol, overview),
            getNextWinSteps(enemy.symbol, overview)
        );


        if (game.forcedSection == null && game.log().isEmpty()) {
          sendResult(data, 4, 4);
          return;
        }
        int boardIndex =
            game.forcedSection == null ? chooseNextBoard(context, game) : game.forcedSection;
        Symbol[] board = game.board[boardIndex];

        List<Integer> preferred = getPreferred(context, game, board);
        int fieldIndex = getNextFieldIndex(me.symbol, board, preferred);

        sendResult(data, boardIndex, fieldIndex);
      } else if (game.type == GameType.RESULT) {

        if (me.score == 1) {
          System.out.printf("WIN: %s (turns=%s)%n", game.id, game.log.size());
        } else if (enemy.score == 1) {
          System.out.printf("LOSE: %s (turns=%s)%n", game.id, game.log.size());
          dumpLog(game, "LOSE");
        } else {
          System.out.printf("DRAW: %s (turns=%s)%n", game.id, game.log.size());
          dumpLog(game, "DRAW");
        }
      }
    });

    socket.open();
  }

  private static List<Integer> getPreferred(Context context, Game game, Symbol[] currentBoard) {
    Player me = context.me;
    Player enemy = context.enemy;
    Symbol[] overview = context.overview;
    Symbol[][] boards = game.board;

    List<Integer> deadFields = getDeadSteps(me.symbol, overview);
    List<Integer> deadFieldsEnemy = getDeadSteps(enemy.symbol, overview);
    List<Integer> finished = getFinished(overview);

    ArrayList<Integer> indexList = new ArrayList<>(all);
//    sortByWinningCount(indexList, me.symbol, currentBoard);

    List<Integer> nextTurnWins = new ArrayList<>();
    List<Integer> nextTurnWinsEnemy = new ArrayList<>();

    // Alle entfernen in dennen der gegner als nächstes gewinnt
    for (int i = 0; i < boards.length; i++) {
      if (!getNextWinSteps(me.symbol, boards[i]).isEmpty()) {
        nextTurnWins.add(i);
      }
      if (!getNextWinSteps(enemy.symbol, boards[i]).isEmpty()) {
        nextTurnWinsEnemy.add(i);
      }
    }

    nextTurnWins.removeAll(deadFields);
    nextTurnWinsEnemy.removeAll(deadFieldsEnemy);

    indexList.removeAll(nextTurnWinsEnemy);

    // in liste nach oben
    sortContainingUp(indexList, deadFieldsEnemy);

    // in liste nach unten
    sortContainingDown(indexList, finished);
    sortContainingDown(indexList, nextTurnWins);

    return indexList;
  }

  private static int getNextFieldIndex(Symbol me, Symbol[] board, List<Integer> preferred) {
    Symbol enemy = getOther(me);

    // win in next step
    List<Integer> nextWinSteps = getNextWinSteps(me, board);
    for (Integer nextWinStep : preferred) {
      if (nextWinSteps.contains(nextWinStep)) {
        return nextWinStep;
      }
    }
    if (!nextWinSteps.isEmpty()) {
      return nextWinSteps.get(0);
    }

    List<Integer> nextWinStepsEnemy = getNextWinSteps(enemy, board);
    for (Integer nextWinStep : preferred) {
      if (nextWinStepsEnemy.contains(nextWinStep)) {
        return nextWinStep;
      }
    }
    if (!nextWinStepsEnemy.isEmpty()) {
      return nextWinStepsEnemy.get(0);
    }

    List<Integer> secondNextWinSteps = getSecondNextWinSteps(me, board);
    sortByWinningCount(preferred, me, board);
    for (Integer nextWinStep : preferred) {
      if (secondNextWinSteps.contains(nextWinStep)) {
        return nextWinStep;
      }
    }
    if (!secondNextWinSteps.isEmpty()) {
      return secondNextWinSteps.get(0);
    }

    return getRandomFreeIndex(board, preferred);
  }


  private static void sortContainingUp(ArrayList<Integer> toSort,
      List<Integer> container) {
    toSort.sort((o1, o2) -> {
      if (container.contains(o1) && !container.contains(o2)) {
        return -1;
      } else if (container.contains(o2) && !container.contains(o1)) {
        return 1;
      }
      return 0;
    });
  }

  private static void sortContainingDown(ArrayList<Integer> toSort,
      List<Integer> container) {
    toSort.sort((o1, o2) -> {
      if (container.contains(o1) && !container.contains(o2)) {
        return 1;
      } else if (container.contains(o2) && !container.contains(o1)) {
        return -1;
      }
      return 0;
    });
  }

  private static void sortByWinningCount(List<Integer> toSort, Symbol me, Symbol[] board) {
    Symbol other = getOther(me);
    toSort.sort((o1, o2) -> {
      long o1Count = winnings.stream()
          .filter(winning -> winning.contains(o1)
              && winning.stream()
              .allMatch(winningIndex -> board[winningIndex] != other))
          .count();
      long o2Count = winnings.stream()
          .filter(winning -> winning.contains(o2)
              && winning.stream()
              .allMatch(winningIndex -> board[winningIndex] != other))
          .count();
      return Long.compare(o2Count, o1Count);
    });
  }

  private static List<Integer> getFinished(Symbol[] board) {
    ArrayList<Integer> result = new ArrayList<>(all);
    for (int i = 0; i < board.length; i++) {
      if (board[i] != null) {
        result.add(i);
      }
    }
    return result;
  }

  private static List<Integer> getDeadSteps(Symbol symbol, Symbol[] board) {
    Symbol other = getOther(symbol);
    List<Integer> result = new ArrayList<>();
    for (int i = 0; i < board.length; i++) {
      boolean isDead = winnings.stream()
          .allMatch(winning -> winning.stream()
              .anyMatch(it -> board[it] == other));
      if (isDead) {
        result.add(i);
      }
    }

    return result;
  }

  private static Symbol getOther(Symbol symbol) {
    return symbol == Symbol.X ? Symbol.O : Symbol.X;
  }

  private static List<Integer> getNextWinSteps(Symbol symbol, Symbol[] board) {
    List<Integer> result = new ArrayList<>();

    for (int i = 0; i < board.length; i++) {
      if (board[i] == symbol) {
        for (List<Integer> winning : winnings) {
          if (winning.contains(i)) {
            ArrayList<Integer> indexList = new ArrayList<>(winning);
            indexList.remove((Object) i);
            if (board[indexList.get(1)] == symbol && board[indexList.get(0)] == null) {
              result.add(indexList.get(0));
            } else if (board[indexList.get(0)] == symbol && board[indexList.get(1)] == null) {
              result.add(indexList.get(1));
            }
          }
        }
      }
    }

    return result;
  }

  private static int chooseNextBoard(Context context, Game game) {
    return getNextFieldIndex(context.me.symbol, context.overview, new ArrayList<>(List.of(4)));
  }
  private static List<Integer> getSecondNextWinSteps(Symbol symbol, Symbol[] board) {
    Set<Integer> set = new LinkedHashSet<>();

    for (int i = 0; i < board.length; i++) {
      if (board[i] == symbol) {
        for (List<Integer> winning : winnings) {
          if (winning.contains(i)) {
            ArrayList<Integer> indexList = new ArrayList<>(winning);
            indexList.remove((Object) i);
            if (board[indexList.get(0)] == null && board[indexList.get(1)] == null) {
              set.add(indexList.get(1));
              set.add(indexList.get(0));
            }
          }
        }
      }
    }

    return new ArrayList<>(set);
  }
  private static Symbol[] overviewToBoard(String[] overview) {
    Symbol[] board = new Symbol[9];
    for (int i = 0; i < overview.length; i++) {
      Symbol symbol = switch (overview[i]) {
        case "X" -> Symbol.X;
        case "O" -> Symbol.O;
        case "-" -> Symbol.DRAW;
        default -> null;
      };
      board[i] = symbol;
    }
    return board;
  }

  private static int getRandomFreeIndex(Symbol[] board, List<Integer> preferred) {
    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < board.length; i++) {
      if (board[i] == null) {
        free.add(i);
      }
    }
    for (Integer i : preferred) {
      if (free.contains(i)) {
        return i;
      }
    }
    System.out.println("RANDOM");
    Collections.shuffle(free);
    return free.get(0);
  }

  private static Player getMe(Game game) {
    return game.players.stream().filter(it -> it.id.equals(game.self)).findFirst().orElseThrow();
  }

  private static Player getEnemy(Game game) {
    return game.players.stream().filter(it -> !it.id.equals(game.self)).findFirst().orElseThrow();
  }

  public enum GameType {
    INIT, ROUND, RESULT
  }

  public enum Symbol {
    O, X, DRAW
  }

  public record Game(
      GameType type,
      Integer forcedSection,
      Symbol[][] board,
      List<Player> players,
      List<Object> log,
      String[] overview,
      String self,
      String id
  ) {

  }

  public record Player(
      String id,
      int score,
      Symbol symbol
  ) {

  }

  public record Context(
      Symbol[] overview,
      Player me,
      Player enemy,
      List<Integer> nextWinsOverview,
      List<Integer> nextWinsOverviewEnemy
  ) {

  }

  @SuppressWarnings("VulnerableCodeUsages")
  private static void sendResult(Object[] data, int board, int field) {
//    System.out.printf("sending turn %s - %s%n", board, field);
    JSONArray jsonArray = new JSONArray();

    jsonArray.put(board);
    jsonArray.put(field);

    //hier rufen wir dann auf dem Ack entsprechend unser ergebnis auf
    Ack ack = (Ack) data[data.length - 1];
    ack.call(jsonArray);
  }

  private static void dumpLog(Game game, String prefix) {
    try {
      Path logDir = Path.of("./logs");
      if (!Files.isDirectory(logDir)) {
        Files.createDirectories(logDir);
      }
      Path file = logDir.resolve(prefix + "_" + game.id + ".log");
      System.out.println(file);
      Files.writeString(file, gson.toJson(game));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}