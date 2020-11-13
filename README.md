# Miscelanous loxone stuff

Purpose:

Misc loxone stuff such as mqtt bridge, zigbee etc ...

## Quickstart for impatient

- you know how to create udp inputs in loxone
- you already have custom flashed zigbee usb token (https://www.zigbee2mqtt.io/) 

Install docker, docker-compose, git. Should be as simple as:

    sudo -i
    curl -sSL https://get.docker.com | sh
    apt-get install docker.io docker-compose git wget

but check your distro documentation.

Then clone and initialize this repo:

    git clone https://github.com/dusanmsk/loxone.git
    cd loxone

## Configuration

Create 'config' file, for example by copying 'config.example'. Depending of your setup you should disable some containers
(for example you are running your own mqtt broker, or you don't want to use zigbee), so remove them from "ENABLED_SUITES" property.

Create dedicated user in loxone for mqtt gateway and new virtual udp input for messagess comming from mqtt to loxone.
Setup that's user name and password in config file so mqtt bridge will be able to read data from loxone.
Remember that mqtt bridge will receive only data which is visible to that user so try to login with that user to loxone
web interface and chech everything is ok.

Now build everything:

    ./suite.sh build

If everything was ok, you should start the suite in foreground mode:

    ./suite.sh run

You should see something like
    
    loxone2mqtt_1  | {"message":"Loxone to MQTT gateway started","level":"info"}
    mqtt2loxone_1  | 441 [main] INFO MqttToLoxoneUDP - Connected to mqtt tcp://localhost:1883 and ready         

which means that mqtt brige is receiving data from loxone.

To run on background, do

    ./suite.sh start

and

    ./suite.sh stop

## Zigbee

... is used to bridge zigbee network with loxone using mqtt. For more info go to https://www.zigbee2mqtt.io/, for quickstart
check serial.port in zigbee/zigbee2mqtt/configuration.yaml.template. Also you should change network key for security reasons.
    

### zigbee web management

There are 2 applications with web management interface dedicated to zigbee network.

#### zigbee2mqtt manager

Is responsible for for zigbee <--> mqtt <--> loxone bridging.

##### How it works

Zigbee2mqtt module receives messages from zigbee devices and sends them to mqtt topic 'zigbee2mqtt'.
Loxone_zigbee_gateway listens for that topics and extracts all data coming from zigbee device,
mapping it to udp messages which are then sent to loxone miniserver.

Example:

If zigbee device 0x00158d00044a1146 sends payload:

    {
        "battery":100,
        "voltage":3055,
        "temperature":25.61,
        "humidity":44.2,
        "pressure":975,
        "linkquality":115
    }

then following udp messages will be send to loxone:

    zigbee/0x00158d00044a1146/battery 100
    zigbee/0x00158d00044a1146/voltage 3055
    zigbee/0x00158d00044a1146/temperature 25.61
    zigbee/0x00158d00044a1146/humidity 44.2
    zigbee/0x00158d00044a1146/pressure 975
    zigbee/0x00158d00044a1146/linkquality 115

You should start UDP monitor in loxone, wait until all required messages will be received
and then create virtual udp command for each value you are interested for.

(To get rid of that 0x00158d00044a1146 names you should rename that device, see below) 

For example create analog input with command recognition:

    zigbee/aquara_1/temperature \v

... and you will receive temperature as analog value.

To create oposite way of communication (from loxone to zigbee), you only have to create new "zigbee_out" category
in loxone config. All virtual inputs with that category will be read by the bridge and send to zigbee devices using following naming rules:

Example:

Analog virtual output named "led1_set_brightness" with category "zigbee_out" will be sent to mqtt topic "zigbee/led1/set" with payload { "brightness" : VALUE }.
Zigbee2mqtt will send that brightness command to zigbee device named "led1".

##### Web interface

Open web browser and go to port 8881.

Main window is used to list, pair and rename zigbee devices on network. Click on "Enable" to start pairing new devices.
Now try to pair new zigbee device. When done, click on 'Refresh' button to see if device was sucessfully paired. It will
show up in the list. Then you should click on device's rename button and rename the device to some human understandable form. 

##### Value translation

Loxone work mostly with numeric values. But what to do when your zigbee device uses words instead of numbers (such as yes,no,true,on,off,...)?

To translate these values before sent to loxone, click on Edit button. Now wait until required zigbee message appear in the list (click on refresh).
You will see for example "LUMI : lumi.magnet | contact | open" or something like this. Click on that record, write "1" into "Map to:" field and click "Create mapping" button.
Now loxone will receive "1" instead of "open" for this type of device and this value.

When done with mapping, click on 'Save' button.

Hint - when you are debugging something or you are lost, zigbee logs should give more detailed info what is going on. See orginal zigbee2mqtt.io documentation
to understand mqtt messages produced by zigbee bridge.

#### zigbee2mqttAssistant

Is more generic web interface. It runs on port 8882. This is more generic app to manage/view zigbee network. It doesn't know anything about loxone bridge, but knows much more about zigbee than zigbee2mqtt manager.
You should list devices, show network map etc... See https://github.com/yllibed/Zigbee2MqttAssistant for detailed info.

## Backup and restore

Stop and backup following directories and files:

- data/
- config

That's it.


## Upgrading

TODO


# More detailed info

## Architecture

Everything is based on mqtt so you need to configure and run bridge first. Bridge then receive value changes from loxone web interface
(using node-lox-mqtt-gateway) and sends them to mqtt topic "lox_out". It also sends mqtt messages received from topic 'lox_in' to loxone as udp text messages.

## Zigbee

Project is meant as follow-up to https://www.zigbee2mqtt.io/.

#### Debugging

You should use any mqtt client to monitor mqtt traffic and see if everything is going ok. For example MQTT Explorer, which shows all
traffic such as "Zigbee logs" section of zigbee2mqtt manager.

In fully working enviroment, there must be some messages in topics:

- zigbee2mqtt
- lox_in (if using loxone mapping, you will see messages going from )
- lox_out (if using loxone mapping, there must be 1:1 picture in mqtt messages of what you see in loxone web interface)

You also should check zigbee2mqtt/bridge/logs for more info about zigbee bridge.

## Common stuff:

When running on rpi or another embedded, you should consider to disable logging at all when setup is done.
Set DEBUG=0 in all config files and also set LOGGING_DRIVER=none in .env files. It will not save your ass from sdcard failure (because it will
fail and it is only matter of time), but will reduce disk writes a lot. When running on rpi, consider using an usb ssd instead of sdcards.