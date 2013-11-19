/**
 * 
 * @author andreabiasi
 *
 */
package it.polito.elite.enocean.protocol.serial.v3.network.packet.commoncommand;

import it.polito.elite.enocean.protocol.serial.v3.network.packet.Packet;

/*
 * Order to reset the device
 */
public class CoWrReset extends Packet{
	/*
	 * The byte vector optional may contains the optional data, in this packet type is empty
	 */
	private static byte[] optional;
	/*
	 * The byte vector dataValue contains the command code
	 */
	private static byte[] dataValue;
	public CoWrReset(){
		super(1,0,(byte) 0x05,dataValue,optional);
		dataValue[0] = 0x02; //Command code
	}
}