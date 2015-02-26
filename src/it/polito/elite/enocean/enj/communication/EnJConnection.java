/*
 * EnJ - EnOcean Java API
 * 
 * Copyright 2014 Andrea Biasi, Dario Bonino 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package it.polito.elite.enocean.enj.communication;

import it.polito.elite.enocean.enj.application.devices.EnJPersistentDeviceSet;
import it.polito.elite.enocean.enj.communication.timing.tasks.CancelTeachInTask;
import it.polito.elite.enocean.enj.communication.timing.tasks.EnJDeviceChangeDeliveryTask;
import it.polito.elite.enocean.enj.eep.EEP;
import it.polito.elite.enocean.enj.eep.EEPIdentifier;
import it.polito.elite.enocean.enj.eep.EEPRegistry;
import it.polito.elite.enocean.enj.eep.eep26.A5.A502.A502;
import it.polito.elite.enocean.enj.eep.eep26.A5.A502.A50205;
import it.polito.elite.enocean.enj.eep.eep26.A5.A502.A502TemperatureMessage;
import it.polito.elite.enocean.enj.eep.eep26.F6.F602.F602;
import it.polito.elite.enocean.enj.eep.eep26.F6.F602.F60201;
import it.polito.elite.enocean.enj.eep.eep26.telegram.EEP26Telegram;
import it.polito.elite.enocean.enj.eep.eep26.telegram.EEP26TelegramFactory;
import it.polito.elite.enocean.enj.eep.eep26.telegram.EEP26TelegramType;
import it.polito.elite.enocean.enj.eep.eep26.telegram.FourBSTeachInTelegram;
import it.polito.elite.enocean.enj.eep.eep26.telegram.FourBSTelegram;
import it.polito.elite.enocean.enj.eep.eep26.telegram.RPSTelegram;
import it.polito.elite.enocean.enj.eep.eep26.telegram.UTETeachInTelegram;
import it.polito.elite.enocean.enj.link.EnJLink;
import it.polito.elite.enocean.enj.link.PacketListener;
import it.polito.elite.enocean.enj.model.EnOceanDevice;
import it.polito.elite.enocean.protocol.serial.v3.network.packet.ESP3Packet;
import it.polito.elite.enocean.protocol.serial.v3.network.packet.radio.Radio;

import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The EnOcean for Java (EnJ) connection layer. It decouples link-level
 * communication and protocol management issues from the application logic.
 * Defines standard and "easy to use" methods for writing / reading data from an
 * EnOcean network.
 * 
 * It is typically built on top of an EnJLink instance, e.g.:
 * 
 * <pre>
 * String serialId = &quot;/dev/tty0&quot;;
 * 
 * EnJLink link = new EnJLink(serialId);
 * 
 * EnJConnection connection = new EnJConnection(link);
 * </pre>
 * 
 * @author <a href="mailto:dario.bonino@gmail.com">Dario Bonino</a>
 * @authr <a href="mailto:biasiandrea04@gmail.com">Andrea Biasi </a>
 * 
 */
public class EnJConnection implements PacketListener
{
	// the wrapped link layer
	private EnJLink linkLayer;

	// the set of device listeners to keep updated about device events
	private Set<EnJDeviceListener> deviceListeners;

	// the executor service to run device update tasks
	private ExecutorService deviceUpdateDeliveryExecutor;

	// ------------- TEACH IN -----------------

	// the default teach-in timeout in milliseconds
	public static final int TEACH_IN_TIME = 20000;

	// the teach-in flag
	private boolean teachIn;

	// the teach in timer
	private Timer teachInTimer;

	// the teach in disabling task
	private CancelTeachInTask teachInResetTask;

	// the device to teach-in
	private EnOceanDevice deviceToTeachIn;

	// The set of known devices
	private EnJPersistentDeviceSet knownDevices;

	// The EEP registry
	private EEPRegistry registry;

	/**
	 * Build a connection layer instance on top of the given link layer
	 * instance.
	 * 
	 * @param linkLayer
	 *            The {@link EnJLink} instance upon which basing the connection
	 *            layer.
	 */
	public EnJConnection(EnJLink linkLayer,
			String peristentDeviceStorageFilename)
	{
		// initialize the set of device listeners
		this.deviceListeners = new HashSet<>();

		// initialize the update delivery executor
		this.deviceUpdateDeliveryExecutor = Executors.newCachedThreadPool();

		// initialize the teachIn flag at false
		this.teachIn = false;
		this.deviceToTeachIn = null;

		// initialize the teach in timer
		this.teachInTimer = new Timer();

		// intialize the teach in reset task
		this.teachInResetTask = new CancelTeachInTask(this);

		// store a reference to the link layer
		this.linkLayer = linkLayer;

		// store a reference to the EEPRegistry, this call also triggers dynamic
		// discovery of supported EEPs
		this.registry = EEPRegistry.getInstance();

		// initialize the persistent device store and sets autosave at on
		// TODO: check how to pass the filename here...
		this.knownDevices = new EnJPersistentDeviceSet(
				peristentDeviceStorageFilename, true);

		// add this connection layer as listener for incoming events
		this.linkLayer.addPacketListener(this);
	}

	/**
	 * Adds a device listener to the set of listeners to be notified about
	 * device events: creation, modification, deletion.
	 * 
	 * @param listener
	 *            The {@link EnJDeviceListener} to notify to.
	 */
	public void addEnJDeviceListener(EnJDeviceListener listener)
	{
		// store the listener in the set of currently active listeners
		this.deviceListeners.add(listener);
	}

	/**
	 * Removes a device listener from the ste of listeners to be notified about
	 * device events.
	 * 
	 * @param listener
	 *            The {@link EnJDeviceListener} to remove.
	 * @return true if removal was successful, false, otherwise.
	 */
	public boolean removeEnJDeviceListener(EnJDeviceListener listener)
	{
		return this.deviceListeners.remove(listener);
	}

	/**
	 * Enables the teach in procedure, it forces the connection layer to listen
	 * for teach-in requests coming from the physical network. whenever a new
	 * teach-in request is detected, a device recognition process is started
	 * enabling access to the newly discovered device.
	 * 
	 * New devices are transfered to the next layer by means of a listener
	 * mechanism.
	 * 
	 * The teach in procedure lasts for a time equal to the default
	 * <code>EnJConnection.TEACH_IN_TIME</code>
	 */
	public void enableTeachIn()
	{
		// start reset timer
		this.enableTeachIn(EnJConnection.TEACH_IN_TIME);
	}

	public void enableTeachIn(String hexDeviceAddress,
			String eepIdentifierAsString)
	{
		if ((hexDeviceAddress != null) && (!hexDeviceAddress.isEmpty())
				&& (eepIdentifierAsString != null)
				&& (!eepIdentifierAsString.isEmpty()))
		{
			// convert - parse strings to corresponding data

			// allowed format for EEPIdentifier is with or without dashes
			if (eepIdentifierAsString.contains("-"))
				eepIdentifierAsString.replaceAll("-", "");

			// trim leading and trailing spaces
			eepIdentifierAsString = eepIdentifierAsString.trim();

			// parse the identifier
			EEPIdentifier eepIdentifier = EEPIdentifier
					.parse(eepIdentifierAsString);

			// trim leading and trailing spaces around the device address
			hexDeviceAddress = hexDeviceAddress.trim();

			// prepare the byte[] for hosting the address
			byte address[] = new byte[4];

			// parse the address
			if (hexDeviceAddress.length() == 8)
			{

				for (int i = 0; i < hexDeviceAddress.length(); i += 2)
				{
					address[(i / 2)] = Byte.parseByte("0x"
							+ eepIdentifierAsString.substring(i, i + 2));
				}
			}

			EnOceanDevice device = new EnOceanDevice(address, null);
			device.setEEP(this.registry.getEEP(eepIdentifier));

			// store the device to learn
			this.deviceToTeachIn = device;

			// start reset timer
			this.enableTeachIn(EnJConnection.TEACH_IN_TIME);
		}
	}

	/**
	 * Enables the teach in procedure, it forces the connection layer to listen
	 * for teach-in requests coming from the physical network. whenever a new
	 * teach-in request is detected, a device recognition process is started
	 * enabling access to the newly discovered device.
	 * 
	 * New devices are transfered to the next layer by means of a listener
	 * mechanism.
	 * 
	 * @param teachInTime
	 *            the maximum time for which the connection layer will accept
	 *            teach in requests.
	 */
	public void enableTeachIn(int teachInTime)
	{
		if (!this.teachIn)
		{
			// enable teach in
			this.teachIn = true;

			// start the teach in reset timer
			this.teachInTimer.schedule(this.teachInResetTask, teachInTime);
		}
	}

	/**
	 * Checks if the connection layer is currently accepting teach-in requests
	 * or not
	 * 
	 * @return the teachIn true if the connection layer is accepting teach-in
	 *         requests, false otherwise.
	 */
	public boolean isTeachInEnabled()
	{
		return teachIn;
	}

	/**
	 * Disables the teach mode on the connection layer. Teach-in requests are
	 * handlePacket * ignored.
	 */
	public void disableTeachIn()
	{
		// stop any pending timer
		this.teachInTimer.cancel();
		this.teachInTimer.purge();

		// disable the teach in procedure
		this.teachIn = false;
		this.deviceToTeachIn = null;
	}

	// TODO: change this to abstract the link layer packet composition!!
	public void sendRadioCommand(byte[] address, byte[] payload)
	{
		// build the link-layer packet
		Radio enjLinkPacket = Radio.getRadio(address, payload, true);

		// send the packet
		this.linkLayer.send(enjLinkPacket);
	}

	@Override
	/**
	 * Handles packets received at the link layer
	 */
	public void handlePacket(ESP3Packet pkt)
	{
		EEP26Telegram telegram = EEP26TelegramFactory.getEEP26Telegram(pkt);

		if ((this.teachIn)
				&& (telegram.getTelegramType() == EEP26TelegramType.UTETeachIn))
		{
			this.handleUTETeachIn((UTETeachInTelegram) telegram);
		}
		else
		{
			// get the sender id, i.e., the address of the device generating the
			// packet
			byte address[] = telegram.getAddress();

			// get the corresponding device...
			EnOceanDevice device = this.knownDevices.getByLowAddress(address);

			// check null
			if (device == null)
			{
				// the device has never been seen before,
				// therefore the device must be learned, either
				// implicitly or
				// explicitly

				// check if the packet is an RPS one
				if (RPSTelegram.isRPSPacket(pkt))
				{
					// handle RPS teach-in, can either be done implicitly, an
					// F60201 EEP will be used, or explicitly if teachIn is true
					// and the device to teach in has been completely specified.
					device = this.handleRPSTeachIn(pkt);
				}
				else if (FourBSTelegram.is4BSPacket(pkt))
				{
					// handle 3 variations of 4BS teach in: explicit with
					// application-specified EEP, explicit with device-specified
					// EEP or bi-directional.
					device = this.handle4BSTeachIn(pkt);
				}

			}
			else
			// the device is already known therefore message handling can be
			// delegated
			{

				// delegate to the device
				EEP deviceEEP = device.getEEP();

				// check not null
				if (deviceEEP != null)
				{
					if (!deviceEEP.handleProfileUpdate(telegram))
					{
						// TODO: log the error
					}
				}
				else
				{
					// TODO: log the error
				}
			}
		}
	}

	/**
	 * Handles the UTE teach-in process, it can either result in a new device
	 * being added, in a teach-in procedure disabling or it could just refuse
	 * the physical teach-in request if not supported.
	 * 
	 * @param pkt
	 *            The teach-in request packet
	 */
	private void handleUTETeachIn(UTETeachInTelegram pkt)
	{
		// the possible response to send back to the transceiver
		UTETeachInTelegram response = null;

		// check the packet type
		if (pkt.isTeachInRequest())
		{
			// check the eep
			if (this.registry.isEEPSupported(pkt.getEEP()))
			{
				// build the response packet
				response = pkt
						.buildResponse(UTETeachInTelegram.BIDIRECTIONAL_TEACH_IN_SUCCESSFUL);

				// build the device
				EnOceanDevice device = new EnOceanDevice(pkt.getAddress(),
						pkt.getManId());

				// add the supported EEP
				device.setEEP(this.registry.getEEP(pkt.getEEP()));

				// store the device locally
				this.knownDevices.add(device);

				// notify the new device discovery
				this.notifyEnJDeviceListeners(device,
						EnJDeviceChangeType.CREATED);
			}
			else
				// build the response packet
				response = pkt
						.buildResponse(UTETeachInTelegram.BIDIRECTIONAL_TEACH_IN_REFUSED);
		}
		else if (pkt.isTeachInDeletionRequest())
		{
			// stop the learning process
			this.disableTeachIn();

			// check if response is required
			// build the response packet
			response = pkt
					.buildResponse(UTETeachInTelegram.BIDIRECTIONAL_TEACH_IN_DELETION_ACCEPTED);
		}
		else if (pkt.isNotSpecifiedTeachIn())
		{
			// currently not supported
			response = pkt
					.buildResponse(UTETeachInTelegram.BIDIRECTIONAL_TEACH_IN_REFUSED);
		}

		if ((pkt.isResponseRequired()) && (response != null)
				&& (response.isResponse()))
		{
			// send the packet back to the transceiver, with high
			// priority as a maximum 500ms latency is allowed.
			this.linkLayer.send(response.getRawPacket(), true);
		}
	}

	private EnOceanDevice handleRPSTeachIn(ESP3Packet pkt)
	{
		// parse the packet
		RPSTelegram rpsTelegram = new RPSTelegram(pkt);

		// build a new RPS device,
		EnOceanDevice device = new EnOceanDevice(rpsTelegram.getAddress(), null);

		// TODO: as per the EEP specification there is no "way" to
		// identify which variant of EEP is using the RPS message,
		// therefore some "heuristic" shall be actuated to assign
		// the right EEP, by default F60201 will be assigned.
		Class<? extends EEP> eep = this.registry.getEEP(new EEPIdentifier(
				F602.rorg, F602.func, F60201.type));
		if (eep != null)
		{
			device.setEEP(eep);

			// notify listeners
			this.notifyEnJDeviceListeners(device, EnJDeviceChangeType.CREATED);

			// store the device
			this.knownDevices.add(device);
		}

		return device;

	}

	private EnOceanDevice handle4BSTeachIn(ESP3Packet pkt)
	{
		// parse the packet
		FourBSTelegram bs4Telegram = new FourBSTelegram(pkt);

		// prepare the device to return
		EnOceanDevice device = null;

		// actually everything shall be ignored unless teach-in is
		// enabled
		if (this.teachIn)
		{
			// check if the received packet is teach in
			if (FourBSTeachInTelegram.isTeachIn(bs4Telegram))
			{
				// wrap the telegram
				FourBSTeachInTelegram bs4TeachInTelegram = new FourBSTeachInTelegram(
						bs4Telegram);

				// --------- Teach-in variation 2 ------
				if (bs4TeachInTelegram.isWithEEP())
				{
					// build a new 4BS device,
					device = new EnOceanDevice(bs4TeachInTelegram.getAddress(),
							bs4TeachInTelegram.getManId());

					// get the right EEP
					Class<? extends EEP> eep = this.registry
							.getEEP(new EEPIdentifier(bs4TeachInTelegram
									.getRorg(),
									bs4TeachInTelegram.getEEPFunc(),
									bs4TeachInTelegram.getEEPType()));

					if (eep != null)
					{
						device.setEEP(eep);

						// notify listeners
						this.notifyEnJDeviceListeners(device,
								EnJDeviceChangeType.CREATED);

						// store the device
						this.knownDevices.add(device);
					}
				}
				else if(this.deviceToTeachIn != null)
				{
					//TODO: check if the address of the device and the address of the telegram match
					
					// build a new 4BS device,
					device = new EnOceanDevice(bs4TeachInTelegram.getAddress(),
							null);

					// get the right EEP
					Class<? extends EEP> eep = this.registry
							.getEEP(new EEPIdentifier(A502.rorg, A502.func,
									A50205.type));

					if (eep != null)
					{
						device.setEEP(eep);

						// notify listeners
						this.notifyEnJDeviceListeners(device,
								EnJDeviceChangeType.CREATED);

						// store the device
						this.knownDevices.add(device);
					}
				}

			}
		}
		else
		{
			// log
			A502TemperatureMessage msg = new A502TemperatureMessage(
					bs4Telegram.getPayload());
			System.out
					.println(40.0 * (225 - (double) msg.getTemperature()) / 255.0);
			System.out.println(msg.isTeachIn());
		}

		return device;
	}

	private void notifyEnJDeviceListeners(EnOceanDevice device,
			EnJDeviceChangeType changeType)
	{
		// use asynchronous delivery here, to avoid blocking / delaying the
		// messaging procedure
		this.deviceUpdateDeliveryExecutor
				.execute(new EnJDeviceChangeDeliveryTask(device, changeType,
						this.deviceListeners));
	}

}