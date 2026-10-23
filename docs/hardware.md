# Hardware

## Digital scale

The app talks to the scale through one interface (`Hardware.DigitalScale`: `read()` and `tare()`). Until the real device
is chosen there is a **simulator** that behaves like a real scale: put something on it and the display jitters before it
settles; you can tare it, overload it, make it never settle, or "unplug" it.

### How the scale is used

| Where | What the operator does | What the app records |
|---|---|---|
| **Register a truck** (supervisor) | Add each compartment with its capacity, what is loaded and **its fuel**: a truck can carry diesel in one compartment and petrol in another. There is no truck-level fuel or rum type: each fuel type is marked with one particular rum type, set at head office (web app: Admin -> Fuel types), so the rum the truck needs follows from its fuels. | The compartments with their fuel and rum type, the total loaded, and the rum to dose for each compartment (its litres over 1,000 times the dosage). A truck registered by mistake can be **deleted** from the "Registered, waiting to be dosed" list on the same screen - only until its first dose; once any compartment is dosed it cannot be deleted. A deleted marking stays on record (who and when) but counts nowhere. |
| **Dose** (dosing operator) | Dosing is **per compartment**: choose a truck, then dose each compartment on its own against its own target (its litres over 1,000 times the dosage), from a jerrican of the rum type that compartment's fuel is marked with. The screen shows the target and the measured weight side by side with a gauge, and the truck moves on to dispensing when every compartment is dosed. Put the **empty small container** on the scale and press **Tare**. Draw the dose from the opened jerrican while watching the live display. Press **Read scale**, scan the jerrican, confirm and sign. | The **weighed** dose (net of the tared container). It must be within the tolerance of what the truck needs; that amount - not the calculated one - leaves the jerrican, and whether it was read from the scale or typed in is kept. |
| **Refill** (warehouse manager, Jerricans -> Refill jerrican) | Put the **empty jerrican** (received empty, and registered at head office) on the scale and press **Tare**. Fill it from the IBC/drum. Press **Read scale**, scan both barcodes, enter the seal number(s) you fitted (one for a plastic jerrican, two different ones for a metal jerrican) and press **Refill and seal**. | The net weight as the rum filled: added to the jerrican, taken from the IBC/drum, and the jerrican remembers which IBC/drum it came from. The IBC/drum must hold the rum type the jerrican is registered for. |
| **Warehouse manager's menu** | The left menu has a **Jerricans** group - **Receive jerrican** (an empty jerrican that head office registered), **Refill jerrican** (fill it from an IBC/drum and seal it), **Transfer jerrican** (send sealed jerricans or containers to a site) and **Receive returns** (take in what was sent to the warehouse, e.g. finished jerricans coming back) - and, below it, **Receive IBC / drum**, **Stock count**, **Inventory**, **Incidents** and **Reports**. | Each operation is a screen of its own. |
| **Stock count** (warehouse manager) | Put each IBC/drum on the scale (tare the pallet first), select its row and press **Read scale for selected**, or double-click the Counted cell and type the kg. Check the differences, then sign. | The counted rum per container against what the system held. A count is a record only - it does not change the stock - and it is sent to head office (web app: Stock counts). |
| **Reconcile stock** (supervisor, Reconcile screen) | Any time during a shift, open **Reconcile** in the left menu (the **Reconcile stock** button on the Shift screen takes you there too). Tare an empty jerrican of the same kind, put each opened jerrican on the scale, select its row and press **Read scale for selected** (or type the kg). For a jerrican that is short, choose the cause (spillage, leakage or other) and add a note; then sign. | Each opened jerrican counted against what the system held. **Sealed jerricans are counted too, not weighed** (that would break their seals): for each one, say whether it was found with its seal intact, not found, or found with a broken or different seal. A jerrican that is **not found** is recorded as an incident for the rum it held and marked MISSING (it can no longer be opened or sent); a **broken seal** is recorded and flagged for head office. Every sealed jerrican must be checked before the reconciliation can be signed ("All found, seals intact" ticks the rest at once).  A shortfall becomes an **incident** of the chosen type and the system stock is corrected to what is really there; extra rum is only noted. The signed reconciliation is sent to head office (web app: Stock counts, kind *Shift reconciliation*). |
| **Transfers** (dispatch / receive) | No scale: scan the sealed containers that are going and press **Dispatch**; at the other site tick what arrived and press **Receive**. No signature is asked for - the record shows who sent and received it, and when. | Which containers moved, who signed, and when each was received. |
| **Incident** (any operator) | Record spilled, leaked or otherwise lost rum: the quantity in grams, the type, and **which rum it was**. Name a container and the rum is the one that container holds (and its volume is reduced); name none and choose the rum type from the list. Incidents raised by a reconciliation or by declaring a jerrican empty carry the rum type automatically. | The incident with its rum type; head office lists it (web app: Incidents, with a Rum type column and filter). |
| **Before the shift starts** (supervisor) | Only **Shift** and **Reports** are available. Press **Start shift** on the Shift tab and the other screens (the Jerricans menu, Register truck, Verify, Incidents, Inventory) appear. Closing the shift locks them again. | A supervisor can only work with containers, trucks and incidents during an open shift; the system refuses it otherwise, not just the menu. |
| **Mobile sites and locations** (supervisor, head office) | A site is *fixed* or *mobile* (Admin > Sites in the web app). Head office keeps the list of places a mobile site can work in Admin > Locations; sites receive it automatically and keep it offline. At a **mobile site** the supervisor must choose where they are working in the Shift screen before **Start shift** will work; at a fixed site the choice is optional. A mobile site carries its own jerricans and rum, refilled and supplied through transfers exactly like a fixed site. | The location is recorded on the shift and sent to head office with it: the web app shows it on Shifts and Markings (with a Location filter), on the marking detail and, per site, as "last worked at" in the sites overview. |
| **Jerricans menu** (supervisor) | The left menu has a **Jerricans** group with all the jerrican operations: **Open jerrican** (check the seals and open it), **Close jerrican** (close it back with a new seal), **Declare empty** (a used-up jerrican: its seals are discarded), **Transfer jerrican** (send empties back to a warehouse) and **Receive jerrican** (take in what was sent to the site). Press the group name to fold or show it. | Each screen shows only its own jerricans; an opened jerrican moves from Open to Close, and a closed-back one returns to sealed stock. |
| **End of shift** (supervisor) | The Shift tab lists what must be done first: every truck verified, and no jerrican left open. On the *Close jerrican* screen (Jerricans menu), close each opened jerrican that still holds rum **back with a new seal** (the seal it was opened with is broken; one seal for a plastic jerrican, two different ones for a metal one) - it returns to sealed stock for the next shift. A jerrican that is used up is declared empty instead (next row). Only then does **End shift** unlock; closing the shift asks for no signature. The rum reconciliation figures on the Shift tab can be shown in grams or kilograms with the **g | kg** switch next to its heading. | Closed-back jerricans are SEALED again with their rum (shown as "Closed back" in the shift figures, so the reconciliation still adds up), signed by the supervisor. |
| **Finished jerrican** (supervisor) | No scale: on the *Declare empty* screen (Jerricans menu) select the finished jerrican and press **Declare empty & sign** (if the system still shows rum in it, say what happened to it: spillage, leakage or other). Then, on the *Transfer jerrican* screen, scan it, choose the warehouse and press **Dispatch** (no signature). Declaring a jerrican empty **discards its seals** (the numbers are remembered and can never be used again), so it travels back without any seal; the warehouse manager ticks it off as received, it arrives EMPTY and is sealed again with new seals when it is refilled. | The jerrican is EMPTY (any rum left is an incident), then in transit, then EMPTY at the warehouse (web app: Transfers, kind *Empties back*). |

The **dispense** step does not use the scale: the dispense operator pours the dosed container into the truck and signs.

In every case the app waits for a **steady** reading and tells the operator when the pan is empty, the reading will not
settle, the scale is overloaded, or it is not responding. A live readout (grams, 0.1 g) shows while dosing and refilling.

**The dose tolerance** is `fuel-marking.dose-tolerance-percent` (default **2.0**, the agreed value for now).
For a truck needing 100 g, 98.0-102.0 g is accepted; anything else is refused with "add more rum" or "start again with a
fresh container". Typing a number instead of reading the scale is allowed (a broken scale must not stop the site) but is
marked **manual** on the record, in reports, and in the web app, where it shows as "entered manually".

**Resolution.** Dosing is a matter of grams, so the scale (and the simulator) work to **0.1 g** and the protocol carries grams.

### Using the simulator in the app (default)
Start the app as usual: a small **Scale simulator** window opens beside it.
1. **Dosing**: sign in as a dosing operator, select a registered truck. In the simulator window type `0.350` and press
   *Place on scale* (the empty small container), then press **Tare scale** in the app - the live display reads 0.0 g.
   Type `0.450` and *Place on scale* (350 g container + 100 g of rum), press **Read scale** - 100.0 g is filled in -
   scan the jerrican, confirm and sign. Try `0.440` (90 g): the dose is refused.
2. **Refilling**: as the warehouse manager, place `0.420` (the empty jerrican), **Tare scale**, place `1.320`, **Read scale** - 900.0 g.
3. **Transfers** do not use the scale.
4. Try the faults: *Unsteady*, *Overloaded*, *Unplugged* - the app shows the matching message instead of a weight.

### Using the simulator as a separate "device" (what the real scale will look like)
```bash
# terminal 1: the simulator, listening on 127.0.0.1:5025
java -cp target/fuel-marking-desktop.jar org.jai.fuelmarking.hardware.ScaleSimulatorLauncher 5025
# terminal 2: the app, reading it over TCP exactly as it will read the real device
java -Dhardware.scale.driver=tcp -Dhardware.scale.tcp.port=5025 -jar target/fuel-marking-desktop.jar
```
The app reconnects by itself if you restart the simulator. The simulator listens on the loopback interface only.

### Settings (`application.yml`, `-D...`, or environment variables such as `HARDWARE_SCALE_DRIVER`)
| Setting | Default | Meaning |
|---|---|---|
| `hardware.scale.driver` | `simulator` | `simulator` (in-app), `tcp` (device or standalone simulator), `serial` (the real device - to add) |
| `hardware.scale.simulator-panel` | `true` | Show the simulator window when the driver is `simulator`. Set `false` on a machine that is not for testing |
| `hardware.scale.tcp.host` / `.port` | `localhost` / `5025` | Where the `tcp` driver connects |
| `hardware.scale.stable-wait-seconds` | `5` | How long to wait for a steady reading before reporting a problem |

With `driver: serial` (not added yet) *Read scale* shows "The scale driver 'serial' is not available yet" - the app itself
keeps working and weights can still be typed.

### The line protocol the simulator speaks
One text line several times a second, like most RS-232 / USB-serial scales:
```
ST,GS,   16400.0 g     stable, gross weight      US,GS,   16431.2 g   = still settling
OL                     overload
```
`ScaleLineProtocol` also understands `16.40 kg S`, `16.40 kg ?`, plain `16.40 kg`, kilograms, grams and pounds (converted to
kg, keeping sub-gram resolution), and ignores any other line. The app sends `T` + line break to tare.

### Adding the real device
1. **Find its output format.** Connect it to a computer and look at the lines it sends (a serial terminal, or the maker's
   manual: "continuous output" / "print mode"). Also note the tare command. If the format differs from the above,
   adjust `ScaleLineProtocol.parse` and add the real lines to `ScaleLineProtocolTest` - they are the spec.
2. **Open the port.** Add a small class (e.g. `SerialScale`) that opens the serial port with a serial library
   (jSerialComm is a common choice), then does `new StreamScale("COM3", port.getInputStream(), port.getOutputStream())`.
   `StreamScale` already handles the background reading, "no data for 2 s" detection, overload and tare.
3. **Register it.** In `ScaleFactory.create()` add `case "serial"` returning that class, and add the port name, baud rate
   and parity to `hardware.scale.*` in `application.yml`.
4. **Check it against the simulator first**: run the new class's parsing against recorded lines (`StreamScaleTest` shows
   how to feed lines through a pipe), then try the real scale.

If the device is networked instead of serial, `hardware.scale.driver=tcp` with its address may already work as is.

### Not done yet
* **Tare weights of containers** are not stored: dosing and refilling tare the container on the scale each time, which is
  what the workflow needs. The `containers.tare_weight_kg` column is unused.
* The scale is read in kilograms and rum quantities are in grams (kg x 1000, done for you).

## Barcode scanner
Works as a keyboard device (it types the code into the focused field), which covers most USB scanners. A scanner that
needs its own driver would implement `Hardware.BarcodeScanner`.

## Signature pad
Signatures are drawn on screen (`SignaturePadDialog`). A hardware pad implements `Hardware.SignaturePad` and is installed
in `Ui`; it needs the device's SDK.
