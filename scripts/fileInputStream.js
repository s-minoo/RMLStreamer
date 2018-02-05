var net = require('net')
var inputPort = 5005;
var outputPort = 9000;
var os = require("os");
	
// process file path argument for input data
var filePath = ""
process.argv.forEach(function (val, index, array) {
  if(index==2) {
	filePath = val;
  } 
  if(index==3) {
	inputPort = val;
  }
});

// create input tcp server
var inputServer = net.createServer(function(socket) {

    console.log("Input Socket connected." + os.EOL + os.EOL)	

    // create line reader
    var lineReader = require('readline').createInterface({
  	input: require('fs').createReadStream(filePath)
    });


    // write each line to the input socket
    lineReader.on('line', function (line) {
	socket.write(line + os.EOL);
    });

    // close socket and tcp servers
    lineReader.on('close', function() {
	socket.end();
	inputServer.close();
    });	

});

// start tcp server
inputServer.listen(inputPort);

console.log("Listening on ports " + inputPort + os.EOL)
