
async function test() {
  const pre = await fetch('/report?process=pre&fileId=5348024557502464', {method: 'GET'});
  const preresp = await pre.json();

  const post = await fetch('/report?process=post&start=0', {method: 'GET'});
  const postresp = await post.json();
   return [preresp, postresp];
}


async function chart(val){
  const result = await test()
  var preResult = result[0];
  var postResult = result[1];
var narrowDelta = postResult["narrowDeltas"];
var narrowSize = preResult["narrowSize"]; 
var wideDelta = postResult["wideDeltas"];
var narrowSize = preResult["wideSize"]; 
var data1 = new Array();
var data2 = new Array();
var narrow = "Narrow Memory";
var wide = "Wide Memory";
var narrowAlloc = preResult['tensorAllocationNarrow'];
var wideAlloc = preResult['tensorAllocationWide'];
if (val == 1){
    fill(wideAlloc)
}
else{
    fill(narrowAlloc)
}
function fill(memoryAlloc){
for (var i = 0; i < memoryAlloc.length; i++){
    var allocs = memoryAlloc[i]["tensorTileAllocation_"][0]["tensorAllocation_"];
    var tileAllocs = memoryAlloc[i]["tensorTileAllocation_"];
    for (var tile = 0; tile < tileAllocs.length; tile++){
        allocs = tileAllocs[tile]["tensorAllocation_"]
        for (var j = 0; j < allocs.length; j++){
            var alloc = allocs[j];
            var start = 0;
            var end = 0;
            start = alloc["baseAddress_"];
            end = start + alloc["size_"];
            for (var k = start; k < end; k++){
                if (end > narrowSize){
                    //Display the Error message
                    const errorMessage = document.getElementById("error-report");
                    errorMessage.innerHTML = "Allocation with label " + alloc["tensorLabel_"] + " has invalid memory address of " + end + ".";
                    break;
                }
                var datum = {}
                datum.location = k;
                datum.layer = memoryAlloc[i]["layer_"];
                datum.tile = tile;
                datum.filled = false;
                datum.label = alloc["tensorLabel_"]
                data1.push(datum)
            }
        }

    }
}}


for (var i = 0; i < narrowDelta.length; i++) {
    var delta = narrowDelta[i];
    for (var j = 0; j < data1.length; j++){
        var entry = data1[j];
        if (entry.location === delta.memoryAddress && entry.tile == delta.tile && entry.location == delta.memoryAddress && entry.label == delta.tensor)
        {
            entry.filled = true;
        }
    }
}

//change the memory location -- TODO
function change(value) {
    if (value === 1) {
        extractData(data1, wide);
    } else {
        extractData(data2, narrow);
    }
}
//filter the data based on the tile selected 
function filterJSON(json, key, value) {
    var result = [];
    json.forEach(function(val, idx, arr) {
        if (val[key] == value) {
            result.push(val)
        }
    })
    return result;
}

// Get the data
function extractData(rawData, memoryType) {
    var data;
    d3.select('#inds')
        .on("change", function() {
            var sect = document.getElementById("inds");
            var section = sect.options[sect.selectedIndex].value;
            console.log(section)
            data = filterJSON(rawData, 'tile', section);
            //debugger
            //data.forEach(function(d) {
            //    d.tile = +d.tile;
            //    d.active = true;
            // });
            var sortedData = data.slice().sort((a, b) => d3.ascending(a.location, b.location))
            console.log(sortedData[0]);
            displayChart(sortedData, memoryType, section);
        });

    // generate initial graph
    data = filterJSON(rawData, 'tile', '0');
    console.log(data[0])
    var sortedData = data.slice().sort((a, b) => d3.ascending(a.location, b.location))
    displayChart(sortedData, memoryType, '0');
    //displayChart(data2,memoryType, '0');
}

//Set up the chart
var obj = document.getElementById('chart');
var divWidth = obj.offsetWidth;
var margin = {
        top: 10,
        right: 10,
        bottom: 100,
        left: 40
    },
    margin2 = {
        top: 430,
        right: 10,
        bottom: 20,
        left: 40
    },
    width = divWidth - 25,
    height = 500 - margin.top - margin.bottom,
    height2 = 500 - margin2.top - margin2.bottom;
var x = d3.scale.ordinal().rangeBands([0, width], 0),
    x2 = d3.scale.ordinal().rangeBands([0, width], 0),
    y = d3.scale.ordinal().rangeRoundBands([0, height], 0),
    y2 = d3.scale.linear().domain([narrowSize, 0]).range([height2, 0]);
d3.select("svg").remove();
var svg = d3.select("body").append("svg")
    .attr("width", width + margin.left + margin.right)
    .attr("height", height + margin.top + margin.bottom);
var focus = svg.append("g")
    .attr("class", "focus")
    .attr("transform", "translate(" + margin.left + "," + margin.top + ")");
var deltas = svg.append("g")
    .attr("class", "focus")
    .attr("transform", "translate(" + margin.left + "," + margin.top + ")");
var context = svg.append("g")
    .attr("class", "context")
    .attr("transform", "translate(" + margin2.left + "," + margin2.top + ")");
var xAxis = d3.svg.axis().scale(x).orient("bottom"),
    xAxis2 = d3.svg.axis().scale(x2).orient("bottom").tickValues([]),
    yAxis = d3.svg.axis().scale(y).orient("left");

//Draws the chart
function displayChart(data, memoryType, section) {
    console.log(data);
    //Display the memory type
    const displayMemoryType = document.getElementById("memory-type");
    displayMemoryType.innerHTML = memoryType;
    //Display tile 
    const displayTile = document.getElementById("tile");
    displayTile.innerHTML = "Tile " + section;

    colorScale = d3.scale.ordinal().domain([0, d3.max(data, function(d) {
        return d.label;
    })]).range(['#e6194b', '#3cb44b', '#ffe119', '#4363d8', '#f58231', '#911eb4', '#46f0f0', '#f032e6', '#bcf60c', '#fabebe', '#008080', 
    '#e6beff', '#9a6324', '#fffac8', '#800000', '#aaffc3', '#808000', '#ffd8b1', '#000075']);
    grayColorScale = d3.scale.ordinal().domain([0, d3.max(data, function(d) {
        return d.label;
    })]).range(['#DCDCDC', '#D3D3D3', '#C0C0C0', '#BEBEBE', '#989898', '#808080', '#696969', '#555555', '#E5E4E2',
    '#727472', '#928E85', '#708090', '#A9A9A9', '#acacac']);
    layerPosition = d3.scale.ordinal().domain(d3.map(data, function(d) {
        return d.layer;
    })).range([22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0]);
    
    var bars = focus.selectAll('.bar').remove();
    focus.select(".x.axis").remove();
    focus.select(".y.axis").remove();
    //update scales
    
    x.domain(data.map(function(d) {
        return d.location
    }));
    y.domain(data.map(function(d) {
        return d.layer
    }));
    x2.domain(data.map(function(d) {
        return d.location
    }));
    console.log(x.domain());
    //make axis
    focus.append("g")
        .attr("class", "x axis")
        .attr("transform", "translate(0," + height + ")")
        .call(xAxis);

    focus.append("g")
        .attr("class", "y axis")
        .call(yAxis);

    //add brush
    var brush = d3.svg.brush()
        .x(x2)
        .on("brush", brushed);
    
    enter(data, focus)
    updateScale(data)

    var subBars = context.selectAll('.subBar')
        .data(data)
    subBars.enter().append("rect")
        .classed('subBar', true)
        .attr({
            height: function(d) {
                return 10;
            },
            width: function(d) {
                return x.rangeBand()
            },
            x: function(d) {
                return x2(d.location);
            },
            y: function(d) {
                return 10
            }
        })

    context.append("g")
        .attr("class", "x axis")
        .attr("transform", "translate(0," + height2 + ")")
        .call(xAxis2);

    context.append("g")
        .attr("class", "x brush")
        .call(brush)
        .selectAll("rect")
        .attr("y", -10)
        .attr("height", 50);

    function brushed() {
        var selected = null;
        var count = 0;
        selected = x2.domain()
            .filter(function(d) {
                if ((brush.extent()[0] <= x2(d)) && (x2(d) <= brush.extent()[1])) {
                    count ++;
                }
                return (brush.extent()[0] <= x2(d)) && (x2(d) <= brush.extent()[1]);
            });
        console.log(selected)
        console.log(count);
        var start;
        var end;

        if (brush.extent()[0] != brush.extent()[1]) {
            start = selected[0];
            end = selected[selected.length - 1] + 1;
        } else {
            start = 0;
            end = data.length;
        }
        var updatedData = data.slice(start, end);

        update(updatedData);
        enter(updatedData, focus);
        exit(updatedData);
        updateScale(updatedData)

    }
    
    function updateScale(data) {
        var tickScale = d3.scale.pow().range([data.length/2, 0]).domain([data.length, 0]).exponent(.5)
        var brushValue = brush.extent()[1] - brush.extent()[0];
        if (brushValue === 0) {
            brushValue = width;
        }
        //console.log(brush.extent()[1]);
        //console.log(brush.extent()[0]);
        var tickValueMultiplier = Math.ceil(Math.abs(tickScale(brushValue)));
        console.log(brushValue, tickScale(brushValue), tickValueMultiplier)

        var filteredTickValues = data.filter(function(d, i) {
            return i % tickValueMultiplier === 0
        }).map(function(d) {
            return d.location
        })
       focus.select(".x.axis").call(xAxis.tickValues(filteredTickValues));
        //focus.select(".y.axis").call(yAxis);
        console.log(filteredTickValues);
    }

    function update(data) {

        x.domain(data.map(function(d) {
            return d.location
        }));
        //y.domain([0, 23]);
        y.domain(data.map(function(d) {
            return d.layer
        }));
        console.log(data);
        var bars = focus.selectAll('.bar')
            .data(data)
        bars
            .attr({
                height: function(d, i) {
                    return 16;
                },
                width: function(d) {
                    return x.rangeBand()
                },
                x: function(d) {
                    return x(d.location);
                },
                y: function(d) {
                    return 9 + y(d.layer)
                },
                fill: function(d) {
                    if(d.filled){
                        return colorScale(d.label);
                    }
                    return grayColorScale(d.label);
                },
                stroke: function(d) {
                    if(d.filled){
                        return colorScale(d.label);
                    }
                    return grayColorScale(d.label);
                }
            })
    }

    function exit(data) {
        var bars = focus.selectAll('.bar').data(data)
        bars.exit().remove()
    }

    function enter(data, focus) {
        //console.log(data.length);
        x.domain(data.map(function(d) {
            return d.location
        }));
        //y.domain([0, d3.max(data, function(d) { return d.layer;})]);
        y.domain(data.map(function(d) {
            return d.layer
        }));
        // var dataFilter = data.map(function(d){return {time: d.time, value:d[selectedGroup]} })

        var bars = focus.selectAll('.bar')
            .data(data)
        bars.enter().append("rect")
            .style("stroke-linejoin", "round")
            .classed('bar', true)
            .attr({
                height: function(d, i) {
                    var newHeight = 410 / data.length;
                    return 16;
                },
                width: function(d) {
                    return x.rangeBand()
                },
                x: function(d) {
                    return x(d.location);
                },
                y: function(d) {
                    return 9 + y(d.layer) 
                },
                fill: function(d) {
                    if(d.filled){
                        return colorScale(d.label);
                    }
                    return grayColorScale(d.label);
                },
                stroke: function(d) {
                    if(d.filled){
                        return colorScale(d.label);
                    }
                    return grayColorScale(d.label);
                }
            })
    }

}
if (val == 2){
    extractData(data1, narrow)
}
else{
    extractData(data1, wide)
}
}
//})()
