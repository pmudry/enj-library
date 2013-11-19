/**
 * 
 * @author andreabiasi
 *
 */
package it.polito.elite.enocean.protocol.serial.v3.network.packet.commoncommand;

import it.polito.elite.enocean.protocol.serial.v3.network.packet.Packet;
/*
 * Read supplies filter
 */
public class CoRdFilter extends Packet{
	/*
	 * The byte vector optional may contains the optional data, in this packet type is empty
	 */
	private static byte[] optional;
	/*
	 * The byte vector dataValue contains:
	 */
	private static byte[] dataValue;

	public CoRdFilter(){
		super(1, 0, (byte)0x05, dataValue, optional);
		dataValue[0] = 0x0F;
	}
}