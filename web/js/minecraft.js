function createMinecraftHead(player,size,completed,failed) {
	var faceImage = new Image();
	faceImage.onload = function() {
		completed(faceImage);
	};
	faceImage.onerror = function() {
		failed();
	};
	if(size == 'body')
		faceImage.src = dynmap.options.tileUrl + 'faces/body/' + player + '.png';
	else
		faceImage.src = dynmap.options.tileUrl + 'faces/' + size + 'x' + size + '/' + player + '.png';
}

function getMinecraftHead(player,size,completed) {
	createMinecraftHead(player, size, completed, function() {
		console.error('Failed to retrieve face of "', player, '" with size "', size, '"!')
	});
}

function getMinecraftTime(servertime) {
	servertime = parseInt(servertime);
	var day = servertime >= 0 && servertime < 13700;
	return {
		servertime: servertime,
		days: parseInt((servertime+8000) / 24000),
		
		// Assuming it is day at 8:00
		hours: (parseInt(servertime / 1000)+8) % 24,
		minutes: parseInt(((servertime / 1000) % 1) * 60),
		seconds: parseInt(((((servertime / 1000) % 1) * 60) % 1) * 60),
		
		day: day,
		night: !day
	};
}

function chat_encoder(message) {
    if (dynmap.options.cyrillic) {
    	if(message.source === 'player') {
        	var utftext = "";
        	for (var n = 0; n < message.text.length; n++) {
	        	var c = message.text.charCodeAt(n);
            	if (c >= 192) {
            	var c = message.text.charCodeAt(n);
                	utftext += String.fromCharCode(c+848);
            	}
            	else if (c == 184) { utftext += String.fromCharCode(1105); }
            	else {
                	utftext += String.fromCharCode(c);
            	}
        	}
        	return utftext
	    }
    }
    return message.text;
}