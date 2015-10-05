/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rabbitchat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author christangga & jeffhorus
 */
public class RabbitChatClient {

    private static final String EXCHANGE_LOGS = "logs";
    private static final String EXCHANGE_USERS = "users";
    private static final String EXCHANGE_CHANNELS = "channels";

    private final Connection connection;
    private final Channel channel;

    private ArrayList<String> users = new ArrayList<>();
    private HashMap<String, String> channels = new HashMap<>();
    private String username = "guest" + String.valueOf(System.currentTimeMillis() / 1000L);

    public RabbitChatClient(String host) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        connection = factory.newConnection();
        channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_LOGS, "fanout");
        channel.exchangeDeclare(EXCHANGE_USERS, "fanout");
        channel.exchangeDeclare(EXCHANGE_CHANNELS, "direct");

        String logsQueue = channel.queueDeclare().getQueue();
        channel.queueBind(logsQueue, EXCHANGE_LOGS, "");

        String usersQueue = channel.queueDeclare().getQueue();
        channel.queueBind(usersQueue, EXCHANGE_USERS, "");

        Consumer logsConsumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope,
                AMQP.BasicProperties properties, byte[] body) throws IOException {
                String sender = new String(body, "UTF-8");
                sender = sender.substring(0, sender.indexOf(": "));

                String message = new String(body, "UTF-8");
                message = message.substring(message.indexOf(": ") + 2);

                if (!sender.equals(username)) {
                    System.out.println(message);
                }
            }
        };
        channel.basicConsume(logsQueue, true, logsConsumer);

        Consumer usersConsumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope,
                AMQP.BasicProperties properties, byte[] body) throws IOException {
                String sign = new String(body, "UTF-8").substring(0, 1);
                String name = new String(body, "UTF-8").substring(1);

                switch (sign) {
                    case "+":
                        users.add(name);
                        break;
                    case "-":
                        users.remove(name);
                        break;
                }
            }
        };
        channel.basicConsume(usersQueue, true, usersConsumer);
    }

    public void login() throws IOException {
        channel.basicPublish(EXCHANGE_USERS, "", null, ("+" + username).getBytes("UTF-8"));

        String message = username + ": User " + username + " logged in";
        channel.basicPublish(EXCHANGE_LOGS, "", null, message.getBytes("UTF-8"));

        System.out.println("Welcome, " + username + "!");
    }

    public void setNickname(String newName) throws IOException {
        if (users.contains(newName)) {
            System.out.println("User " + newName + " already exists");
        } else {
            String message = newName + ": User " + username + " changed his name to " + newName;

            channel.basicPublish(EXCHANGE_USERS, "", null, ("-" + username).getBytes("UTF-8"));
            username = newName;
            channel.basicPublish(EXCHANGE_USERS, "", null, ("+" + username).getBytes("UTF-8"));

            channel.basicPublish(EXCHANGE_LOGS, "", null, message.getBytes("UTF-8"));

            System.out.println("Successfully changed name to " + username);
        }
    }

    public void joinChannel(String channelName) throws IOException {
        if (channels.containsKey(channelName)) {
            System.out.println("Already a member of channel " + channelName);
        } else {
            String channelsQueue = channel.queueDeclare().getQueue();
            channel.queueBind(channelsQueue, EXCHANGE_CHANNELS, channelName);

            channels.put(channelName, channelsQueue);

            Consumer channelsConsumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                    AMQP.BasicProperties properties, byte[] body) throws IOException {
                    String message = new String(body, "UTF-8");
                    System.out.println(message);
                }
            };
            channel.basicConsume(channelsQueue, true, channelsConsumer);

            String message = username + ": User " + username + " joined channel " + channelName;
            channel.basicPublish(EXCHANGE_LOGS, "", null, message.getBytes("UTF-8"));

            System.out.println("Successfully joined channel " + channelName);
        }
    }

    public void leaveChannel(String channelName) throws IOException {
        if (!channels.containsKey(channelName)) {
            System.out.println("Not a member of channel " + channelName);
        } else {
            channel.queueUnbind(channels.get(channelName), EXCHANGE_CHANNELS, channelName);

            channels.remove(channelName);

            String message = username + ": User " + username + " left channel " + channelName;
            channel.basicPublish(EXCHANGE_LOGS, "", null, message.getBytes("UTF-8"));

            System.out.println("Successfully left channel " + channelName);
        }
    }

    public void broadcast(String message) throws IOException {
        for (String channelName : channels.keySet()) {
            String header = "[" + channelName + "] (" + username + ") ";
            channel.basicPublish(EXCHANGE_CHANNELS, channelName, null, (header + message).getBytes("UTF-8"));
        }

        System.out.println("Message sent!");
    }

    public void chat(String channelName, String message) throws IOException {
        if (!channels.containsKey(channelName)) {
            System.out.println("Not a member of channel " + channelName);
        } else {
            String header = "[" + channelName + "] (" + username + ") ";
            channel.basicPublish(EXCHANGE_CHANNELS, channelName, null, (header + message).getBytes("UTF-8"));

            System.out.println("Message to channel " + channelName + " sent!");
        }
    }

    public void exit() throws IOException, TimeoutException {
        channel.basicPublish(EXCHANGE_USERS, "", null, ("-" + username).getBytes("UTF-8"));

        String message = username + ": User " + username + " exited chat system";
        channel.basicPublish(EXCHANGE_LOGS, "", null, message.getBytes("UTF-8"));

        channel.close();
        connection.close();

        System.out.println("Goodbye, " + username + "!");
    }

    /**
     * @param args the command line arguments
     * @throws java.io.IOException
     * @throws java.util.concurrent.TimeoutException
     */
    public static void main(String[] args) throws IOException, TimeoutException {
        final RabbitChatClient client = new RabbitChatClient("localhost");

        client.login();

        Scanner scn = new Scanner(System.in);
        String input = "";
        while (!input.toLowerCase().startsWith("/exit")) {
            input = scn.nextLine();

            if (input.startsWith("/")) {
                String[] command = input.split(" ");
                command[0] = command[0].toLowerCase();
                switch (command[0]) {
                    case "/nick":
                        if (command.length < 2) {
                            System.out.println("Invalid command!");
                            System.out.println("/nick <username>");
                            break;
                        }

                        client.setNickname(command[1]);
                        break;
                    case "/join":
                        if (command.length < 2) {
                            System.out.println("Invalid command!");
                            System.out.println("/join <channelname>");
                            break;
                        }

                        client.joinChannel(command[1]);
                        break;
                    case "/leave":
                        if (command.length < 2) {
                            System.out.println("Invalid command!");
                            System.out.println("/leave <channel>");
                            break;
                        }

                        client.leaveChannel(command[1]);
                        break;
                    case "/exit":
                        client.exit();
                        break;
                    default:
                        System.out.println("Invalid command!");
                        System.out.println("/nick <username>");
                        System.out.println("/join <channelname>");
                        System.out.println("/leave <channel>");
                        System.out.println("/exit");
                        System.out.println("<message>");
                        System.out.println("@<channelname> <message>");
                }
            } else if (input.startsWith("@")) {
                String[] command = input.split(" ", 2);
                if (command.length < 2) {
                    System.out.println("Invalid command!");
                    System.out.println("@<channelname> <message>");
                    continue;
                }

                client.chat(command[0].substring(1), command[1]);
            } else {
                client.broadcast(input);
            }
        }
    }

}
