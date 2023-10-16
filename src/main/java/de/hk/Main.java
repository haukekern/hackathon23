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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;

public class Main {

  public static final String SECRET = "5d36622b-4d05-43ad-9b15-dfb71a7ceca3"; //Das Secret des Bot
  public static final String GAMESERVER = "https://games.uhno.de"; //URL zum Gameserver

  public static final Gson gson = new Gson();

  private static List<Integer[]> winnings = new ArrayList<>();


  public static void main(String... args) {
    System.out.println("BOT GEHT AN!");

    winnings.add(new Integer[]{0, 1, 2});
    winnings.add(new Integer[]{3, 4, 5});
    winnings.add(new Integer[]{6, 7, 8});
    winnings.add(new Integer[]{0, 3, 6});
    winnings.add(new Integer[]{1, 4, 7});
    winnings.add(new Integer[]{2, 5, 8});
    winnings.add(new Integer[]{0, 4, 8});
    winnings.add(new Integer[]{2, 4, 6});

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

    final ThreadPoolExecutor executor =
        (ThreadPoolExecutor) Executors.newFixedThreadPool(10);

    socket.on("data", (data) -> {
      executor.submit(() -> {
        String json = String.valueOf(data[0]);
//      System.out.println("json: " + json);

        Game game = gson.fromJson(json, Game.class);
//      System.out.println("game: " + game);
        Player me = getMe(game);
//      System.out.println("me: " + game);

        if (game.type == GameType.INIT) {
          System.out.println("neue runde: " + game.id);
        }

        if (game.type == GameType.ROUND) {
          try {
            Integer forcedSection = game.forcedSection;
            Integer zeile = null;
            Integer spalte = null;
            if (forcedSection != null) {
              zeile = forcedSection / 3;
              spalte = forcedSection % 3;
            }

            String[] board1 = new String[9];
            for (int i = 0; i < board1.length; i++) {
              board1[i] = "";
            }



            for (int i = 0; i < game.board.length; i++) {
              Symbol[] current = game.board[i];
              int add = (i / 3) * 3;
              int row1 = add;
              int row2 = 1 + add;
              int row3 = 2 + add;
              board1[row1] += symbolToString(current[0]) + symbolToString(current[1]) + symbolToString(current[2]);
              board1[row2] += symbolToString(current[3]) + symbolToString(current[4]) + symbolToString(current[5]);
              board1[row3] += symbolToString(current[6]) + symbolToString(current[7]) + symbolToString(current[8]);

            }

            int[] monte = Solution.MonteCarloTS(board1,
                (zeile != null ? new int[]{zeile, spalte} : new int[]{-1, 0}),
                me.symbol.toString().charAt(0));

            System.out.println("laut monte: " + monte[0] + "|" + monte[1]);

            //monte 1-7
            //udo

            int iboard = monte[0] / 3; //0
            int jboard = monte[1] / 3; //2
            int icell = monte[0] % 3; //1
            int jcell = monte[1] % 3; //1

            int board = 3 * iboard + jboard;
            int cell = 3 * icell + jcell;

            sendResult(data, board, cell);
            return;
          } catch (Exception ex) {
            ex.printStackTrace();
          }

          int boardIndex = game.forcedSection == null ? chooseNextBoard(game) : game.forcedSection;
//        System.out.println("wir gehen auf board: " + boardIndex + " (forced: " + game.forcedSection != null + ")");

          Symbol[] board = game.board[boardIndex];
          int calcIndex = calculateNextSet(board, me);
//        int fieldIndex = getRandomFreeIndex(board);

          sendResult(data, boardIndex, calcIndex);
        }

        if (game.type == GameType.RESULT) {
          int myWins = 0;
          int enemyWins = 0;

          for (Player player : game.players) {
            if (player.symbol == me.symbol) {
              myWins = player.score;
            } else {
              enemyWins = player.score;
            }
          }
          if (myWins == 0 && enemyWins == 0) {
            System.out.println(
                "RESULT: DRAW runde: " + game.id);
          } else {
            System.out.println(
                "RESULT: " + (myWins > enemyWins ? "WIN" : "LOSS") + " runde: " + game.id + " nach "
                    + game.log.size() + " zuegen");

            if(enemyWins > myWins) {
              dumpLog(game, "loss");
            }
          }
        }
      });

    });

    socket.open();
  }

  private static String symbolToString(Symbol symbol) {
    return symbol == null ? "-" : symbol.toString();
  }

  private static int calculateNextSet(Symbol[] board, Player me) {
    List<Integer> my = new ArrayList<>();
    List<Integer> enemy = new ArrayList<>();
    for (int i = 0; i < board.length; i++) {
      if (board[i] == me.symbol) {
        my.add(i);
      } else if (board[i] != null) {
        enemy.add(i);
      }
    }

    Integer winningField = checkWinningConditions(board, my, enemy, me);

    if (winningField != null) {
      System.out.println("wir können gewinnen!");
      return winningField;
    }

    Integer preventLossField = checkWinningConditions(board, enemy, my, me);
    if (preventLossField != null) {
      System.out.println("gegner könnte gewinnen -> NOPE!");
      return preventLossField;
    }

//    Integer edge = getNextEdge(board);
//    if (edge != null) {
//      System.out.println("wir setzen in die ecke");
//      return edge;
//    }

    System.out.println("random!?!?!?!");
    return getRandomFreeIndex(board);
  }

  private static Integer getNextEdge(Symbol[] board) {
    if (board[0] == null) {
      return 0;
    }
    if (board[2] == null) {
      return 2;
    }
    if (board[6] == null) {
      return 6;
    }
    if (board[8] == null) {
      return 8;
    }

    return null;
  }

  private static Integer checkWinningConditions(Symbol[] board, List<Integer> my,
      List<Integer> enemy, Player me) {
    for (Integer[] win : winnings) {
      int count = 0;
      for (Integer myPlace : my) {
        for (Integer winRow : win) {
          if (myPlace == winRow) {
            count++;
            break;
          }
        }
      }

      if (count == 2) {
        //hier pruefen ob wir den win setzen koennen
        for (int i = 0; i < board.length; i++) {
          if (board[i] == null) {
            for (Integer winningInteger : win) {
              if (i == winningInteger) {
//                System.out.println("DER SOLL GEWINNEN: " + i);
                return i;
              }
            }
          }
        }
      }
    }

    return null;
  }

  private static int chooseNextBoard(Game game) {
    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < game.overview.length; i++) {
      if (StringUtils.isBlank(game.overview[i])) {
        free.add(i);
      }
    }
    Collections.shuffle(free);
//    System.out.println("  wir shuffeln in " + free.get(0) + " als board");
    return free.get(0);
  }

  private static int getRandomFreeIndex(Symbol[] board) {
    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < board.length; i++) {
      if (board[i] == null) {
        free.add(i);
      }
    }
    Collections.shuffle(free);
    return free.get(0);
  }

  private static Player getMe(Game game) {
    return game.players.stream().filter(it -> it.id.equals(game.self)).findFirst().orElseThrow();
  }

  @SuppressWarnings("VulnerableCodeUsages")
  private static void sendResult(Object[] data, int board, int field) {
//    System.out.printf(" wir setzen %s - %s%n", board, field);
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

  public enum GameType {
    INIT, ROUND, RESULT
  }

  public enum Symbol {
    O, X
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

}
