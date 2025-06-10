/**
 * 
 */
package scr;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * @author Daniele Loiacono
 * 
 */
public class SocketHandler {

	private InetAddress address;
	private int port;
	private DatagramSocket socket;
	private final boolean verbose;

	public SocketHandler(String host, int port, boolean verbose) {

		// set remote address
		try {
			this.address = InetAddress.getByName(host);
		} catch (UnknownHostException e) {
			if (verbose) {
				System.err.println("Unknown host: " + host);
			}
			throw new RuntimeException("Failed to resolve host: " + host, e);
		}
		this.port = port;
		// init the socket
		try {
			socket = new DatagramSocket();
		} catch (SocketException e) {
			if (verbose) {
				System.err.println("Failed to create socket: " + e.getMessage());
			}
			throw new RuntimeException("Failed to create UDP socket", e);
		}
		this.verbose = verbose;
	}

	public void send(String msg) {

		if (verbose)
			System.out.println("Sending: " + msg);
		try {
			byte[] buffer = msg.getBytes();
			socket.send(new DatagramPacket(buffer, buffer.length, address, port));
		} catch (IOException e) {
			if (verbose) {
				System.err.println("Failed to send message: " + e.getMessage());
			}
			throw new RuntimeException("Failed to send UDP packet", e);
		}
	}

	public String receive() {
		try {
			byte[] buffer = new byte[1024];
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
			socket.receive(packet);
			String received = new String(packet.getData(), 0, packet.getLength());
			if (verbose)
				System.out.println("Received: " + received);
			return received;
		} catch (SocketTimeoutException se) {
			if (verbose)
				System.out.println("Socket Timeout!");
		} catch (IOException e) {
			if (verbose) {
				System.err.println("IO exception while receiving: " + e.getMessage());
			}
		}
		return null;
	}

	public String receive(int timeout) {
		try {
			socket.setSoTimeout(timeout);
			String received = receive();
			socket.setSoTimeout(0);
			return received;
		} catch (SocketException e) {
			if (verbose) {
				System.err.println("Socket exception when setting timeout: " + e.getMessage());
			}
		}
		return null;
	}

	public void close() {
		socket.close();
	}

}
