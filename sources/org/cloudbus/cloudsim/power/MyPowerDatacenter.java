package org.cloudbus.cloudsim.power;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DataCloudTags;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.File;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.MyCloudlet;
import org.cloudbus.cloudsim.MyDatacenter;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.MyCloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.util.WriteToResultFile;

/**
 * My Power-aware Datacenter is a Datacenter which overwrites all methods to be in accordance with power-aware ability.
 * 
 * @author Baptiste Louis
 * 
 */
public class MyPowerDatacenter extends MyDatacenter {

	/** The Total Storage Energy in Joule(s). */
	private double	totalStorageEnergy;

	/** The Total Datacenter Energy in Joule(s). */
	private double	totalDatacenterEnergy;

	/**
	 * Creates a new datacenter.
	 * 
	 * @param name
	 *            the name
	 * @param characteristics
	 *            the res configurations
	 * @param schedulingInterval
	 *            the scheduling interval
	 * @param vmAllocationPolicy
	 *            the vm provisioner
	 * @param storageList
	 *            the storage list
	 * @throws Exception
	 *             the exception
	 */
	public MyPowerDatacenter(String name, DatacenterCharacteristics characteristics,
			VmAllocationPolicy vmAllocationPolicy, List<MyPowerHarddriveStorage> storageList, double schedulingInterval)
			throws Exception {
		super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);

		// sets initial variables
		setTotalDatacenterEnergy(0.0);
		setTotalStorageEnergy(0.0);
	}

	/* (non-Javadoc)
	 * 
	 * @see cloudsim.Datacenter#processCloudletSubmit(cloudsim.core.SimEvent, boolean) */
	@Override
	protected void processCloudletSubmit(SimEvent ev, boolean ack) {
		/**
		 * When a cloudlet is received by the Datacenter, First (and once for all) it retrieves requiredFiles and adds
		 * dataFiles by interacting with the persistent storage available.
		 */
		processCloudletFiles(ev, ack);
	}

	/**
	 * Process Cloudlet Required Files and Data Files.
	 * 
	 * @param ev
	 * @param ack
	 */
	public void processCloudletFiles(SimEvent ev, boolean ack) {

		// retrieve Cloudlet object
		Cloudlet cl = (Cloudlet) ev.getData();
		WriteToResultFile.setTempRowNum(cl.getCloudletId());

		// print out Cloudlet reception
		Log.printLine();
		Log.formatLine("\n%.6f: %s: Cloudlet # %d has been successfully received. ", CloudSim.clock(), getName(),
				cl.getCloudletId());

		// initializes local variable
		double timeFrameStorageEnergy = 0.0;

		// Handle requiredFiles
		List<String> requiredFiles = new ArrayList<String>();
		if (cl instanceof MyCloudlet) {
			MyCloudlet mycl = (MyCloudlet) cl;
			requiredFiles = mycl.getRequiredFiles();
		}

		if (requiredFiles != null) {
			Iterator<String> iter = requiredFiles.iterator();

			while (iter.hasNext()) {
				String fileName = iter.next();

				for (MyPowerHarddriveStorage storage : this.<MyPowerHarddriveStorage> getStorageList()) {
					File tempFile = storage.getFile(fileName);

					if (tempFile != null) {

						// add energy to this time frame storage energy
						timeFrameStorageEnergy += processOperationWithStorage(storage, tempFile, cl, "retrieved");
						break;
					}
				}
			}
		}

		// Handle dataFiles
		List<File> dataFiles = new ArrayList<File>();
		if (cl instanceof MyCloudlet) {
			MyCloudlet mycl = (MyCloudlet) cl;
			dataFiles = mycl.getDataFiles();
		}

		if (dataFiles != null) {
			Iterator<File> iter = dataFiles.iterator();

			while (iter.hasNext()) {
				File tempFile = iter.next();

				// Might need to move this in Datacenter.java
				int answerTag = this.addFile(tempFile);

				// test if tempFile has been added
				if (answerTag == DataCloudTags.FILE_ADD_SUCCESSFUL) {

					// find where the file has been added
					for (MyPowerHarddriveStorage storage : this.<MyPowerHarddriveStorage> getStorageList()) {

						// test if the storage id EQUAL the file ResourceID
						if (storage.getId() == tempFile.getResourceID()) {

							// add energy to this time frame storage energy
							timeFrameStorageEnergy += processOperationWithStorage(storage, tempFile, cl, "added");
						}
					}
				} else if (answerTag == DataCloudTags.FILE_ADD_ERROR_EXIST_READ_ONLY) {
					Log.printLine(tempFile.getName() + ".addFile(): Warning - This file named <" + tempFile.getName()
							+ "> is already stored");
				}
			}
		}

		// Update total energy
		setTotalDatacenterEnergy(getTotalDatacenterEnergy() + timeFrameStorageEnergy);
		setTotalStorageEnergy(getTotalStorageEnergy() + timeFrameStorageEnergy);
	}

	/**
	 * Process operation with the storage.
	 * 
	 * @param storage
	 * @param tempFile
	 * @param cl
	 * @param action
	 * @return the energy
	 */
	protected double processOperationWithStorage(MyPowerHarddriveStorage storage, File tempFile, Cloudlet cl,
			String action) {

		// retrieve the transaction time for this operation
		double tempTime = tempFile.getTransactionTime();

		// add the transaction time to the "total active time" of this disk
		storage.setInActiveDuration(storage.getInActiveDuration() + tempTime);

		// set the mode of the disk to Active + handle waitingDelay, eventDelay, ActiveEndAt and IdleIntervalsHistory.
		double waitingDelay = 0.0;
		double eventDelay = 0.0;
		if (storage.isActive()) {
			waitingDelay = storage.getActiveEndAt() - CloudSim.clock();
			storage.setActiveEndAt(storage.getActiveEndAt() + tempTime);
			eventDelay = storage.getActiveEndAt() - CloudSim.clock();
			// Note: a delay is not a specific Time, it is a duration. A duration is a difference between two times.

		} else if (storage.isIdle()) {
			// handle Idle intervals
			storage.getIdleIntervalsHistory().add(CloudSim.clock() - storage.getLastIdleStartTime());

			// handle Active End Time
			storage.setActiveEndAt(CloudSim.clock() + tempTime);

			// handle Event for Operation completion
			eventDelay = tempTime;

			// handle mode to Active (key = 1)
			storage.setMode(1);
		}

		// compute energy
		double tempPower = storage.getPowerActive();
		double tempEnergy = tempPower * tempTime;

		// handle queue
		storage.setQueueLength(storage.getQueueLength() + 1);

		// Prepare data for the event
		Object[] data = new Object[8];
		data[0] = action; // the action of the operation
		data[1] = cl; // the cloudlet subject to the operation
		data[2] = tempFile; // the file subject to the operation
		data[3] = tempPower; // the power needed during the operation
		data[4] = tempTime; // the transaction time of the operation
		data[5] = tempEnergy; // the energy consumed by the operation
		data[6] = storage; // the disk subject to the operation
		data[7] = waitingDelay; // the waiting time for the operation

		// Schedule an Event confirming that the read/write operation has been done.
		send(this.getId(), eventDelay, MyCloudSimTags.CLOUDLET_FILE_DONE, data);

		return tempEnergy;
	}

	/* (non-Javadoc)
	 * 
	 * @see org.cloudbus.cloudsim.MyDatacenter#processCloudletFilesDone(org.cloudbus.cloudsim.core.SimEvent) */
	protected void processCloudletFilesDone(SimEvent ev) {

		// Retrieves data from the ev
		Object[] data = (Object[]) ev.getData();
		String action = (String) data[0];
		Cloudlet cl = (Cloudlet) data[1];
		File tempFile = (File) data[2];
		double tempPower = (double) data[3];
		double tempTime = (double) data[4];
		double tempEnergy = (double) data[5];
		MyPowerHarddriveStorage storage = (MyPowerHarddriveStorage) data[6];
		double waitingDelay = (double) data[7];

		// store results/information
		WriteToResultFile.AddValueToSheetTab(waitingDelay, cl.getCloudletId(), 2);
		WriteToResultFile.AddValueToSheetTab(tempTime, cl.getCloudletId(), 3);
		WriteToResultFile.AddValueToSheetTab(CloudSim.clock(), cl.getCloudletId(), 7);
		WriteToResultFile.AddValueToSheetTab(tempFile.getName(), cl.getCloudletId(), 8);
		WriteToResultFile.AddValueToSheetTab(tempFile.getSize(), cl.getCloudletId(), 9);
		WriteToResultFile.AddValueToSheetTab(tempEnergy, cl.getCloudletId(), 10);

		// Print out confirmation that Files have been handled
		Log.formatLine("\n%.6f: %s: Cloudlet # %d: <%s> %s on %s.", CloudSim.clock(), getName(), cl.getCloudletId(),
				tempFile.getName(), action, storage.getName());
		Log.formatLine("%10s Power  consumption of %6.3f Watt(s).", "", tempPower);
		Log.formatLine("%10s Queue Waiting time of %9.6f Seconds(s).", "", waitingDelay);
		Log.formatLine("%10s Transaction time   of %9.6f Seconde(s).", "", tempTime);
		Log.formatLine("%10s Energy consumption of %6.3f Joule(s).", "", tempEnergy);
		Log.printLine();

		// handle queue
		storage.setQueueLength(storage.getQueueLength() - 1);

		// Test if there is further operation on the disk
		if (storage.getActiveEndAt() <= CloudSim.clock()) {
			storage.setMode(0); // switch to idle mode
			storage.setLastIdleStartTime(CloudSim.clock()); // handle Idle intervals
			storage.setActiveEndAt(0.0); // reset EndAt time
		}
	}

	// GETTERs and SETTERs

	/**
	 * Gets the total Energy related to Storage.
	 * 
	 * @return the totalStorageEnergy
	 */
	public double getTotalStorageEnergy() {
		return totalStorageEnergy;
	}

	/**
	 * Sets the total Energy related to Storage.
	 * 
	 * @param totalStorageEnergy
	 *            the totalStorageEnergy to set
	 */
	public void setTotalStorageEnergy(double totalStorageEnergy) {
		this.totalStorageEnergy = totalStorageEnergy;
	}

	/**
	 * Gets the total energy related to the Datacenter.
	 * 
	 * @return the totalDatacenterEnergy
	 */
	public double getTotalDatacenterEnergy() {
		return totalDatacenterEnergy;
	}

	/**
	 * Sets the total energy related to the Datacenter.
	 * 
	 * @param totalDatacenterEnergy
	 *            the totalDatacenterEnergy to set
	 */
	public void setTotalDatacenterEnergy(double totalDatacenterEnergy) {
		this.totalDatacenterEnergy = totalDatacenterEnergy;
	}
}
