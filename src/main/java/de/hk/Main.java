package de.hk;

import com.google.gson.Gson;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;

public class Main {

  public static final String SECRET = "d20d723f-cad9-418f-ab9f-8107018a1cb7"; //Das Secret des Bot
  public static final String GAMESERVER = "https://games.uhno.de"; //URL zum Gameserver

  public static final Gson gson = new Gson();


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
      System.out.println("json: " + json);

      Game game = gson.fromJson(json, Game.class);
//      System.out.println("game: " + game);
      Player me = getMe(game);
//      System.out.println("me: " + game);

      if (game.type == GameType.ROUND) {
        int boardIndex = game.forcedSection == null ? chooseNextBoard(game) : game.forcedSection;
        Symbol[] board = game.board[boardIndex];
        int fieldIndex = getRandomFreeIndex(me.symbol, board);

        sendResult(data, boardIndex, fieldIndex);
      }
    });

    socket.open();
  }

  private static int chooseNextBoard(Game game) {
    if(StringUtils.isNotBlank(game.overview[3])){
      return 3;
    }
    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < game.overview.length; i++) {
      if (StringUtils.isBlank(game.overview[i])) {
        free.add(i);
      }
    }
    Collections.shuffle(free);
    return free.get(0);
  }

  private static int getRandomFreeIndex(Symbol symbol, Symbol[] board) {
    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < board.length; i++) {
      if (board[i] != symbol) {
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
    System.out.printf("sending turn %s - %s%n", board, field);
    JSONArray jsonArray = new JSONArray();

    jsonArray.put(board);
    jsonArray.put(field);

    //hier rufen wir dann auf dem Ack entsprechend unser ergebnis auf
    Ack ack = (Ack) data[data.length - 1];
    ack.call(jsonArray);
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