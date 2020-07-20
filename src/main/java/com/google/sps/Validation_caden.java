package com.google.sps;

import com.google.sps.exceptions.*;
import com.google.sps.results.*;
import com.google.sps.proto.MemaccessCheckerDataProto.*;
import com.google.sps.structures.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class Validation_caden {
  private static MemaccessCheckerData memaccessCheckerData;

  private static List<TensorLayerAllocationTable> tensorLayerAllocationNarrow;
  private static List<TensorLayerAllocationTable> tensorLayerAllocationWide;

  private static Map<Integer, TensorAllocation> tensorLabelToTensorAllocationNarrow;
  private static Map<Integer, TensorAllocation> tensorLabelToTensorAllocationWide;

  private static ArrayList<Instruction> instructions;
  private static Map<Integer, Instruction> instructionTagtoInstruction;
  private static List<TraceEvent> traceEvents;

  private static int[][] narrow;
  private static int[][] wide;

  public static int narrowSize;
  public static int wideSize;

  public static int numTiles;

  // Memory access types.
  public static final String NARROW_READ = "Narrow Read";
  public static final String NARROW_WRITE = "Narrow Write";
  public static final String WIDE_READ = "Wide Read";
  public static final String WIDE_WRITE = "Wide Write";

  public Validation_caden(MemaccessCheckerData memaccessCheckerData) {
    this.memaccessCheckerData = memaccessCheckerData;

    tensorLayerAllocationNarrow = memaccessCheckerData.getTensorLayerAllocationNarrowList();
    tensorLayerAllocationWide = memaccessCheckerData.getTensorLayerAllocationWideList();

    tensorLabelToTensorAllocationNarrow = new Hashtable<Integer, TensorAllocation>();
    tensorLabelToTensorAllocationWide = new Hashtable<Integer, TensorAllocation>();

    traceEvents = memaccessCheckerData.getTraceEventsList();
    instructions = new ArrayList<Instruction>();

    narrowSize = memaccessCheckerData.getNarrowMemorySizeBytes();
    wideSize = memaccessCheckerData.getWideMemorySizeBytes();
    numTiles = memaccessCheckerData.getNumTiles();

    narrow = new int[numTiles][narrowSize];
    wide = new int[numTiles][wideSize];

    instructionTagtoInstruction = new Hashtable<Integer, Instruction>();
  }

  public static PreProcessResults preProcess() {
    boolean isError = false;
    String message = "Preprocessing completed successfully.";

    instructions.addAll(memaccessCheckerData.getInstructionsList());

    relateInstructionTagtoInstructionTable();

    try {
      relateTensorsToInstructions(tensorLayerAllocationNarrow, true);
    } catch (InvalidTensorAddressException e) {
      if (!isError) {
        message = e.getMessage();
        isError = true;
      }    
    } catch (ArrayIndexOutOfBoundsException e) {
      if (!isError) {
        message = e.getMessage();
        isError = true;
      }    
    }

    try {
      relateTensorsToInstructions(tensorLayerAllocationWide, false);
    } catch (InvalidTensorAddressException e) {
      if (!isError) {
        message = e.getMessage();
        isError = true;
      }    
    } catch (ArrayIndexOutOfBoundsException e) {
      if (!isError) {
        message = e.getMessage();
        isError = true;
      }    
    }

    tensorLabelToTensorAllocationNarrow = relateTensorLabelToTensorAllocation(tensorLayerAllocationNarrow);
    tensorLabelToTensorAllocationWide = relateTensorLabelToTensorAllocation(tensorLayerAllocationWide);

    return new PreProcessResults(isError, message, traceEvents.size());
  }

  public static ProcessResults process(long start, long end) {
    try {
    //   validateTraceEvents(start, end);
    } catch (Exception e) {
      return new ProcessResults(e, narrow, wide);
    }

    return new ProcessResults(null, narrow, wide);
  }

  /**
   * Given an array showing the narrow and wide tensor allocations in memory, populates each
   * instruction with the tensor that they operate on. Throws a InvalidTensorAddressException if the
   * instruction operates on a memory address that does not hold a tensor.
   */
  private static void relateTensorsToInstructions(List<TensorLayerAllocationTable> tensorLayerAllocationTable, boolean isNarrow)
      throws InvalidTensorAddressException {
    // Get the layer to instruction map.
    Hashtable<String, List<Integer>> layerToInstructionTable = getLayerToInstructionTable();

    // Loops over each layer in the tensorLayerAllocation table to update their instructions.
    for (TensorLayerAllocationTable tensorLayerAllocation : tensorLayerAllocationTable) {
        String curLayer = tensorLayerAllocation.getLayer();

        // Gets first tileAllocation, this could change if allocations are different across tile. 
        TensorTileAllocationTable tensorTileAllocationTable = tensorLayerAllocation.getTensorTileAllocation(0);

        // Get the list of tensors allocated on the tile.
        ArrayList<TensorAllocation> tensorAllocationList = new ArrayList<TensorAllocation>();
        tensorAllocationList.addAll(tensorTileAllocationTable.getTensorAllocationList());

        // Creates an interval tree holding the address intervals for the tile's tensors allocated. 
        ArrayList<AddressInterval> addressIntervalList = new ArrayList<AddressInterval>();
        for (TensorAllocation tensorAllocation : tensorAllocationList) {
            addressIntervalList.add(new AddressInterval(tensorAllocation));
        }
        IntervalTree<AddressInterval> addressIntervalTree = new IntervalTree<AddressInterval>(addressIntervalList);

        // Gets all of the instructions operating on the current layer.
        List<Integer> layerInstructions = layerToInstructionTable.get(curLayer);

        Instruction.Builder instructionBuilder = Instruction.newBuilder();

        // Loops through the instructions on this layer and updates their tensor based off of the layer's interval tree.
        for (int layerInstructionTag : layerInstructions) {
            instructionBuilder.mergeFrom(instructionTagtoInstruction.get(layerInstructionTag));

            // Gets the corresponding reads and writes, depending on if the memory is narrow or wide.
            ArrayList<Integer> reads = new ArrayList<Integer>();
            ArrayList<Integer> writes = new ArrayList<Integer>();

            if (isNarrow) {
                reads.addAll(instructionBuilder.getNarrowReadList());
                writes.addAll(instructionBuilder.getNarrowWriteList());

            } else {
                reads.addAll(instructionBuilder.getWideReadList());
                writes.addAll(instructionBuilder.getWideWriteList());
            }

            // Gets the operation's corresponding tensors from the interval tree and assigns them.
            for (int i = 0; i < reads.size(); i++) {
                AddressInterval address = new AddressInterval(reads.get(i));
                // Returns the address interval of the tensor that the address falls in
                AddressInterval tensorAddressInterval = addressIntervalTree.containsAddress(address);

                if (tensorAddressInterval == null) {
                    throw new InvalidTensorAddressException(address.start(), layerInstructionTag, isNarrow);
                }
                int tensorLabel = tensorAddressInterval.label();
                
                reads.set(i, tensorLabel);
            }

            for (int i = 0; i < writes.size(); i++) {
                AddressInterval address = new AddressInterval(writes.get(i));
                AddressInterval tensorAddressInterval = addressIntervalTree.containsAddress(address);

                if (tensorAddressInterval == null) {
                    throw new InvalidTensorAddressException(address.start(), layerInstructionTag, isNarrow);
                }

                int tensorLabel = tensorAddressInterval.label();
                
                writes.set(i, tensorLabel);
            }

            if (isNarrow) {
                instructionBuilder.addAllNarrowRead(reads);
                instructionBuilder.addAllNarrowWrite(writes);
            } else {
                instructionBuilder.addAllWideRead(reads);
                instructionBuilder.addAllWideWrite(writes);
            }   

            instructionTagtoInstruction.put(layerInstructionTag, instructionBuilder.build());
        }
    }
    
  }

  /**
   * Returns a map of the layer to the corresponding instructions that operate in that layer.
   */
  private static Hashtable<String, List<Integer>> getLayerToInstructionTable() {
      Hashtable<String, List<Integer>> layerToInstructionTable = new Hashtable<String, List<Integer>>();

      // Loops over the instructions, finds the instruction's layer corresponding instruction list
      // and adds the instruciton to that list
      for (Instruction instruction : instructions) {
          List<Integer> layerInstructions = layerToInstructionTable.get(instruction.getLayer());

          if (layerInstructions == null) {
              layerInstructions = new ArrayList<Integer>();
          }

          layerInstructions.add(instruction.getTag());
      }

      return layerToInstructionTable;
  }

  /** Given a list of instructions, maps each instruction tag to its corresponding instruction. */
  public static void relateInstructionTagtoInstructionTable() {
    for (Instruction instruction : instructions) {
      instructionTagtoInstruction.put(instruction.getTag(), instruction);
    }
  }

  /**
   * Given a list of trace entries, validates that trace entries proceeded in the right order and
   * operated on the correct traces.
   */
  public static void validateTraceEvents(long start, long end)
      throws Exception, InvalidTensorOperationException {
    if (traceEvents.isEmpty()) {
        throw new Exception(
            "No trace entry to be validated "); 
      }
    // Iterates over each trace entry, ensures that it is operating on the correct tensor and
    // validates based on if it is a write or a read.
    for (long i = start; i < end; i++) {
      TraceEvent traceEvent = traceEvents.get((int) i);
      // Gets the trace event's corresponding instruction and ensures it exists.
      Instruction instruction = instructionTagtoInstruction.get(traceEvent.getInstructionTag());
      if (instruction == null) {
        throw new Exception(
            "Instruction with key "
                + traceEvent.getInstructionTag()
                + " does not exist."); // TODO: May need to write custom exception
      }

      TraceEvent.AccessType accessType = traceEvent.getAccessType();
      int address = traceEvent.getAddress();
    
      int traceTensor = -1;
      // try catch would not be necessary because a default value of 0 will be set -- catch the unexpected tensor 
      try {
        traceTensor = getTraceTensor(address, accessType, instruction);
      } catch(Exception e) {
        System.out.println(e.getMessage());
      }

      List<Boolean> masks = instruction.getMaskList();
      if (masks.isEmpty()){
          throw new InvalidMaskException(traceEvent.getInstructionTag(), traceEvent.getAccessType());
      }
      
      // If the trace entry is a write, performs a write validation. If it a read, performs a read
      // validation.
      if (accessType == TraceEvent.AccessType.NARROW_WRITE
          || accessType == TraceEvent.AccessType.WIDE_WRITE) {
          writeValidation(narrow, wide, masks, traceTensor, traceEvent);
      } else if (accessType == TraceEvent.AccessType.NARROW_READ
          || accessType == TraceEvent.AccessType.WIDE_READ) {
        try {
          readValidation(narrow, wide, masks, traceTensor, traceEvent);
        } catch (InvalidTensorReadException e) {
          System.out.println(e.getMessage());
        }
      }
    }
  }
  
  public static Map<Integer, TensorAllocation> relateTensorLabelToTensorAllocation(List <TensorLayerAllocationTable> tensorLayAllocs) {
    Hashtable<Integer, TensorAllocation> tensorLabelToTensorAllocation = new Hashtable<Integer, TensorAllocation>();

    for (TensorLayerAllocationTable tensorLayAlloc : tensorLayAllocs) {
      List <TensorTileAllocationTable> tensorTileAllocs = tensorLayAlloc.getTensorTileAllocationList();
      for (TensorTileAllocationTable tensorTileAlloc : tensorTileAllocs){
          List <TensorAllocation> tensorAllocs = tensorTileAlloc.getTensorAllocationList();
          for (TensorAllocation tensorAlloc : tensorAllocs){
              tensorLabelToTensorAllocation.put(tensorAlloc.getTensorLabel(), tensorAlloc);
          }
      }  
    }

    return tensorLabelToTensorAllocation;
  }

  /**
   * Returns the tensor that the trace entry is operating on based on its corresponding instruction.
   */  
  public static int getTraceTensor(
      int traceAddress, TraceEvent.AccessType traceAccessType, Instruction instruction)
      throws Exception, MemoryAccessException {
    List<Integer> AccessTypeTensorList;
    int tensor = -1;
    // Tracks if the corresponding instruction has the trace entry's access type.
    Boolean hasAccessType = true;

    if (traceAccessType == TraceEvent.AccessType.NARROW_READ) {
      if (instruction.getNarrowReadCount() != 0) {
        AccessTypeTensorList = instruction.getNarrowReadList();
        tensor = getTensor(AccessTypeTensorList, traceAddress, tensorLabelToTensorAllocationNarrow);
      } else {
        hasAccessType = false;
      }
    } else if (traceAccessType == TraceEvent.AccessType.NARROW_WRITE) {
      if (instruction.getNarrowWriteCount() != 0) { 
        AccessTypeTensorList = instruction.getNarrowWriteList();
        tensor = getTensor(AccessTypeTensorList, traceAddress, tensorLabelToTensorAllocationNarrow);
      } else {
        hasAccessType = false;
      }
    } else if (traceAccessType == TraceEvent.AccessType.WIDE_READ) {
      if (instruction.getWideReadCount() != 0) {
        AccessTypeTensorList = instruction.getWideReadList();
        tensor = getTensor(AccessTypeTensorList, traceAddress, tensorLabelToTensorAllocationWide);
      } else {
        hasAccessType = false;
      }
    } else if (traceAccessType == TraceEvent.AccessType.WIDE_WRITE) {
      if (instruction.getWideWriteCount() != 0) {
        AccessTypeTensorList = instruction.getWideWriteList();
        tensor = getTensor(AccessTypeTensorList, traceAddress, tensorLabelToTensorAllocationWide);
      } else {
        hasAccessType = false;
      }
    } else {
      throw new Exception(
          "Trace event at address " 
            + traceAddress 
            + " has invalid access type of " 
            + traceAccessType 
            + ".");
    }

    // Throws MemoryAccessException if instruction does not have the expected
    // access type.
    if (!hasAccessType) {
      throw new MemoryAccessException(traceAccessType, instruction.getTag());
    }

    // Throws an exception if there is no tensor
    // associated with the correct instruction access type.
    
    if (tensor == -1) {
      throw new Exception(
          "Instruction  " 
            + instruction.getName() 
            + " does not have the appropriate tensor associated with it."
            + "This may be due to invalid tensor or incorrect tensor event address. ") ; // notifying incorrect address error 
    }

    return tensor;
  }

  /**
   * Retrieves the correct tensor depending on the specific access type instruction list. 
   * 
   */
  public static int getTensor (List<Integer> accessTypeTensorList, int traceAddress, 
            Map<Integer, TensorAllocation> tensorLabelToTensorAllocationTable) {
      int tensor = -1;
      for (int i = 0; i < accessTypeTensorList.size(); i++) {
            TensorAllocation tensorAlloc = tensorLabelToTensorAllocationTable.get(accessTypeTensorList.get(i));
            int start = tensorAlloc.getBaseAddress();
            int end = start + tensorAlloc.getSize();
            if (traceAddress >= start && traceAddress < end) {
                tensor = accessTypeTensorList.get(i);
                break;
            }
        }
       return tensor; 
  }

  /**
   * Validates that the write validation has a corresponding tensor and writes it to the correct
   * address in the memory arrays.
   */
  public static void writeValidation(
      int[][] narrow, int[][] wide, List<Boolean> masks, int tensor, TraceEvent traceEvent) {  
    int address = traceEvent.getAddress();
    if (traceEvent.getAccessType() == TraceEvent.AccessType.NARROW_WRITE) {
      // Iterate through the tiles.
      for (int tile = 0; tile < numTiles; tile++) {
        if (masks.get(tile)) {
          // Write the tensor name in our replicated memory.
          int endAddress = traceEvent.getBytes()  + address;
          for (int currentByte = address; currentByte < endAddress; currentByte ++) {
            narrow[tile][currentByte] = tensor;
          } 
        }
          }
        }
    if (traceEvent.getAccessType() == TraceEvent.AccessType.WIDE_WRITE) {
      for (int tile = 0; tile < numTiles; tile++) {
        if (masks.get(tile)) {
          int endAddress = traceEvent.getBytes()  + address;
          for (int currentByte = address; currentByte < endAddress; currentByte ++) {
            wide[tile][currentByte] = tensor;
          }  
        }
      }
    }
  }

  /**
   * Validates that the tensor that the read trace entry is reading has been written before the read
   * occurs.
   */
  public static void readValidation(
      int[][] narrow, int[][] wide, List<Boolean> masks, int tensor, TraceEvent traceEvent)
      throws InvalidTensorReadException {
    int address = traceEvent.getAddress();

    if (traceEvent.getAccessType() == TraceEvent.AccessType.NARROW_READ) {
      for (int tile = 0; tile < numTiles; tile++) {
        if (masks.get(tile)) {
          int endAddress = traceEvent.getBytes()  + address;  
          for (int currentByte = address; currentByte < endAddress; currentByte ++) {
            if (narrow[tile][currentByte] != tensor) {
                throw new InvalidTensorReadException(
                    tensor, tile, address, narrow[tile][address], "narrow");
            }
          }
        }
      }
    } else if (traceEvent.getAccessType() == TraceEvent.AccessType.WIDE_READ) {
      for (int tile = 0; tile < numTiles; tile++) {
        if (masks.get(tile)) {
          int endAddress = traceEvent.getBytes()  + address;  
          for (int currentByte = address; currentByte < endAddress; currentByte ++) {  
            if (wide[tile][address] != tensor) {
                throw new InvalidTensorReadException(
                    tensor, tile, address, wide[tile][address], "wide");
            }
          }
        }
      }
    }
  }


    private static class AddressInterval implements Interval {

        private final int label;
        private final int start;
        private final int end;

        public AddressInterval(TensorAllocation tensorAllocation) {
            this.label = tensorAllocation.getTensorLabel();
            this.start = tensorAllocation.getBaseAddress();
            this.end = tensorAllocation.getBaseAddress() + tensorAllocation.getSize();
        }
        
        public AddressInterval(int label, int start, int end) {
            this.label = label;
            this.start = start;
            this.end = end;
        }
        
        public AddressInterval(int start) {
            this.label = -1; // If it is solely one address corresponding to an instruction, it will not a have a label.
            this.start = start;
            this.end = start;
        }

        public int label() {
            return label;
        }
        
        @Override
        public int start() {
            return start;
        }

        @Override
        public int end() {
            return end;
        }
        
        @Override
        public String toString() {
            return "label: " + label + " start: " + start + " end: " + end;
        }
        
        @Override
        public boolean equals(Object other) {
            // No need for null check. The instanceof operator returns false if (other == null).
            if (!(other instanceof AddressInterval)) {
                return false;
            }

            return start == ((AddressInterval) other).start && end == ((AddressInterval) other).end;
        }
        
        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + start;
            result = 31 * result + end;
            return result;
        }
    }
}

