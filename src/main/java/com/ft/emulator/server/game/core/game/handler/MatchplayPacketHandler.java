package com.ft.emulator.server.game.core.game.handler;

import com.ft.emulator.server.game.core.constants.GameFieldSide;
import com.ft.emulator.server.game.core.matchplay.ClientPacket;
import com.ft.emulator.server.game.core.matchplay.GameSessionManager;
import com.ft.emulator.server.game.core.matchplay.basic.MatchplayBasicSingleGame;
import com.ft.emulator.server.game.core.matchplay.room.GameSession;
import com.ft.emulator.server.game.core.matchplay.room.RoomPlayer;
import com.ft.emulator.server.game.core.packet.PacketID;
import com.ft.emulator.server.game.core.packet.packets.S2CWelcomePacket;
import com.ft.emulator.server.game.core.packet.packets.matchplay.C2SMatchplayPlayerIdsInSessionPacket;
import com.ft.emulator.server.game.core.packet.packets.matchplay.S2CMatchplayTeamWinsPoint;
import com.ft.emulator.server.game.core.packet.packets.matchplay.S2CMatchplayTeamWinsSet;
import com.ft.emulator.server.game.core.packet.packets.matchplay.S2CMatchplayTriggerServe;
import com.ft.emulator.server.game.core.packet.packets.matchplay.relay.C2CBallAnimationPacket;
import com.ft.emulator.server.game.core.packet.packets.matchplay.relay.C2CPlayerAnimationPacket;
import com.ft.emulator.server.networking.Connection;
import com.ft.emulator.server.networking.packet.Packet;
import com.ft.emulator.server.shared.module.Client;
import com.ft.emulator.server.shared.module.RelayHandler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Log4j2
public class MatchplayPacketHandler {
    private final GameSessionManager gameSessionManager;
    private final RelayHandler relayHandler;

    public RelayHandler getRelayHandler() {
        return relayHandler;
    }

    public void sendWelcomePacket(Connection connection) {
        S2CWelcomePacket welcomePacket = new S2CWelcomePacket(0, 0, 0, 0);
        connection.sendTCP(welcomePacket);
    }

    public void handleRelayPacketToClientsInGameSessionRequest(Connection connection, Packet packet) {
        Packet relayPacket = new Packet(packet.getData());
        switch (relayPacket.getPacketId()) {
            case PacketID.C2CBallAnimationPacket:
                C2CBallAnimationPacket ballAnimationPacket = new C2CBallAnimationPacket(relayPacket);
                GameSession gameSession = connection.getClient().getActiveGameSession();
                gameSession.setTimeLastBallWasHit(System.currentTimeMillis());
                gameSession.setLastBallHitByTeam(ballAnimationPacket.getPlayerPosition());
                break;
            case PacketID.C2CPlayerAnimationPacket:
                C2CPlayerAnimationPacket playerAnimationPacket = new C2CPlayerAnimationPacket(relayPacket);
                break;
        }

        handleGameSessionState(connection);
        sendPacketToAllClientInSameGameSession(connection, relayPacket);
    }

    public void handleRegisterPlayerForSession(Connection connection, Packet packet) {
        C2SMatchplayPlayerIdsInSessionPacket matchplayPlayerIdsInSessionPacket = new C2SMatchplayPlayerIdsInSessionPacket(packet);

        int playerId = matchplayPlayerIdsInSessionPacket.getPlayerIds().stream().findFirst().orElse(-1);
        int sessionId = matchplayPlayerIdsInSessionPacket.getSessionId();

        GameSession gameSession = this.gameSessionManager.getGameSessionBySessionId(sessionId);
        if (gameSession != null) {
            Client playerClient = gameSession.getClientByPlayerId(playerId);
            playerClient.setActiveGameSession(gameSession);
            playerClient.setRelayConnection(connection);
            connection.setClient(playerClient);
            this.relayHandler.addClient(playerClient);
        }
        else {
            // disconnect all clients maybe? put them back to the room mybe?
        }

        Packet answer = new Packet(PacketID.S2CMatchplayAckPlayerInformation);
        answer.write((byte) 0);
        connection.sendTCP(answer);
    }

    public void handleDisconnect(Connection connection) {
        Client client = connection.getClient();
        if (client == null) return; // server checker will throw null here, since we don't register a client for the connection,
                                    // because originally we want that to do inside handleRegisterPlayerForSession, need solution
        GameSession gameSession = client.getActiveGameSession();
        if (gameSession == null) return;

        client.setActiveGameSession(null);
        gameSession.getClients().removeIf(x -> x.getRelayConnection().getId() == connection.getId());
        if (gameSession.getClients().size() == 0) {
            this.relayHandler.getSessionList().remove(gameSession);
        }
    }

    public void handleUnknown(Connection connection, Packet packet) {
        Packet unknownAnswer = new Packet((char) (packet.getPacketId() + 1));
        if (unknownAnswer.getPacketId() == (char) 0x200E) {
            unknownAnswer.write((char) 1);
        }
        else {
            unknownAnswer.write((short) 0);
        }
        connection.sendTCP(unknownAnswer);
    }

    private void sendPacketToAllClientInSameGameSession(Connection connection, Packet packet) {
        List<Client> clientList = relayHandler.getClientsInGameSession(connection.getClient().getActiveGameSession().getSessionId());
        for (Client client : clientList) {
            client.getRelayConnection().sendTCP(packet);
        }
    }

    private void handleGameSessionState(Connection connection) {
        GameSession gameSession = connection.getClient().getActiveGameSession();
        if (gameSession == null) return;
        if (gameSession.getTimeLastBallWasHit() == -1) return;
        if (gameSession.getLastBallHitByTeam() == -1) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - gameSession.getTimeLastBallWasHit() > TimeUnit.SECONDS.toMillis(3))
        {
            // We need to branch here later for different modes. Best would be without casting haha
            MatchplayBasicSingleGame game = (MatchplayBasicSingleGame) gameSession.getActiveMatchplayGame();
            byte setsTeamRead = game.getSetsPlayer1();
            byte setsTeamBlue = game.getSetsPlayer2();
            if (gameSession.getLastBallHitByTeam() == GameFieldSide.RedTeam) {
                game.setPoints((byte) (game.getPointsPlayer1() + 1), game.getPointsPlayer2());
            }
            else if (gameSession.getLastBallHitByTeam() == GameFieldSide.BlueTeam) {
                game.setPoints(game.getPointsPlayer1(), (byte) (game.getPointsPlayer2() + 1));
            }

            boolean anyTeamWonSet = setsTeamRead != game.getSetsPlayer1() || setsTeamBlue != game.getSetsPlayer2();
            if (anyTeamWonSet) {
                gameSession.setRedTeamPlayerStartY(gameSession.getRedTeamPlayerStartY() * (-1));
                gameSession.setBlueTeamPlayerStartY(gameSession.getBlueTeamPlayerStartY() * (-1));
            }

            List<RoomPlayer> roomPlayerList = connection.getClient().getActiveRoom().getRoomPlayerList();
            List<Client> clients = connection.getClient().getActiveGameSession().getClients();
            for (Client client : clients) {
                RoomPlayer rp = roomPlayerList.stream()
                        .filter(x -> x.getPlayer().getId().equals(client.getActivePlayer().getId()))
                        .findFirst().orElse(null);
                if (rp == null) {
                    continue;
                }

                boolean isRedTeam = rp.getPosition() == 0 || rp.getPosition() == 2;
                if (isRedTeam) {
                    gameSession.setRedTeamPlayerStartX(gameSession.getRedTeamPlayerStartX() * (-1));
                }
                else {
                    gameSession.setBlueTeamPlayerStartX(gameSession.getBlueTeamPlayerStartX() * (-1));
                }

                short winningPlayerPosition = (short) (gameSession.getLastBallHitByTeam() == GameFieldSide.RedTeam ? 0 : 1);
                S2CMatchplayTeamWinsPoint matchplayTeamWinsPoint =
                        new S2CMatchplayTeamWinsPoint(winningPlayerPosition, false, game.getPointsPlayer1(), game.getPointsPlayer2());
                client.getConnection().sendTCP(matchplayTeamWinsPoint);

                if (anyTeamWonSet) {
                    S2CMatchplayTeamWinsSet matchplayTeamWinsSet = new S2CMatchplayTeamWinsSet(game.getSetsPlayer1(), game.getSetsPlayer2());
                    client.getConnection().sendTCP(matchplayTeamWinsSet);
                }
            }

            // Lets try to create only one task for the whole session instead for each player.
            List<ClientPacket> packetsToSend = prepareServePacketsToSend(connection);
            TimerTask task = new TimerTask() {
                public void run() {
                    packetsToSend.forEach(cp -> {
                        packetsToSend.forEach(cpi -> {
                            cp.getClient().getConnection().sendTCP(cpi.getPacket());
                        });
                    });
                }
            };
            Timer timer = new Timer("PointAnimationTimer");
            timer.schedule(task, TimeUnit.SECONDS.toMillis(8));

            gameSession.setTimeLastBallWasHit(-1);
            gameSession.setLastBallHitByTeam(-1);
        }
    }

    private List<ClientPacket> prepareServePacketsToSend(Connection connection) {
        GameSession gameSession = connection.getClient().getActiveGameSession();
        List<RoomPlayer> roomPlayerList = connection.getClient().getActiveRoom().getRoomPlayerList();
        List<Client> clients = connection.getClient().getActiveGameSession().getClients();
        List<ClientPacket> clientPackets = new ArrayList<>();
        for (Client client : clients) {
            RoomPlayer rp = roomPlayerList.stream()
                    .filter(x -> x.getPlayer().getId().equals(client.getActivePlayer().getId()))
                    .findFirst().orElse(null);
            if (rp == null) {
                continue;
            }

            boolean isRedTeam = rp.getPosition() == 0 || rp.getPosition() == 2;
            boolean madePoint = isRedTeam && gameSession.getLastBallHitByTeam() == GameFieldSide.RedTeam ||
                    !isRedTeam && gameSession.getLastBallHitByTeam() == GameFieldSide.BlueTeam;
            float playerStartX = isRedTeam ? gameSession.getRedTeamPlayerStartX() : gameSession.getBlueTeamPlayerStartX();
            float playerStartY = isRedTeam ? gameSession.getRedTeamPlayerStartY() : gameSession.getBlueTeamPlayerStartY();
            S2CMatchplayTriggerServe matchplayTriggerServe = new S2CMatchplayTriggerServe(rp.getPosition(), playerStartX, playerStartY, madePoint);
            ClientPacket clientPacket = new ClientPacket();
            clientPacket.setPacket(matchplayTriggerServe);
            clientPacket.setClient(client);
            clientPackets.add(clientPacket);
        }

        return clientPackets;
    }

    private static Rectangle getGameFieldRectangle() {
        return new Rectangle(new Point(-6300, -12500), new Dimension(12600,25000));
    }
}