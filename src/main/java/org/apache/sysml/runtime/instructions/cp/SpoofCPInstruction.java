/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.instructions.cp;

import java.util.ArrayList;

import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.codegen.CodegenUtils;
import org.apache.sysml.runtime.codegen.SpoofOperator;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.cp.ComputationCPInstruction;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;

public class SpoofCPInstruction extends ComputationCPInstruction
{
	private Class<?> _class = null;
	private int _numThreads = 1;
	private CPOperand[] _in = null;
	
	public SpoofCPInstruction(Class<?> cla, int k, CPOperand[] in, CPOperand out, String opcode, String str) {
		super(null, null, null, out, opcode, str);
		_class = cla;
		_numThreads = k;
		_in = in;
	}
	
	public Class<?> getOperatorClass() {
		return _class;
	}

	public static SpoofCPInstruction parseInstruction(String str) 
		throws DMLRuntimeException 
	{
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		
		ArrayList<CPOperand> inlist = new ArrayList<CPOperand>();
		Class<?> cla = CodegenUtils.loadClass(parts[1]);
		String opcode =  parts[0] + CodegenUtils.getSpoofType(cla);
		
		for( int i=2; i<parts.length-2; i++ )
			inlist.add(new CPOperand(parts[i]));
		CPOperand out = new CPOperand(parts[parts.length-2]);
		int k = Integer.parseInt(parts[parts.length-1]);
		
		return new SpoofCPInstruction(cla, k, inlist.toArray(new CPOperand[0]), out, opcode, str);
	}

	@Override
	public void processInstruction(ExecutionContext ec)
		throws DMLRuntimeException 
	{		
		SpoofOperator op = (SpoofOperator) CodegenUtils.createInstance(_class);
		
		//get input matrices and scalars, incl pinning of matrices
		ArrayList<MatrixBlock> inputs = new ArrayList<MatrixBlock>();
		ArrayList<ScalarObject> scalars = new ArrayList<ScalarObject>();
		for (CPOperand input : _in) {
			if(input.getDataType()==DataType.MATRIX)
				inputs.add(ec.getMatrixInput(input.getName()));
			else if(input.getDataType()==DataType.SCALAR)
				scalars.add(ec.getScalarInput(input.getName(), input.getValueType(), input.isLiteral()));
		}
		
		// set the output dimensions to the hop node matrix dimensions
		if( output.getDataType() == DataType.MATRIX) {
			MatrixBlock out = new MatrixBlock();
			op.execute(inputs, scalars, out, _numThreads);
			ec.setMatrixOutput(output.getName(), out);
		}
		else if (output.getDataType() == DataType.SCALAR) {
			ScalarObject out = op.execute(inputs, scalars, _numThreads);
			ec.setScalarOutput(output.getName(), out);
		}
		
		// release input matrices
		for (CPOperand input : _in)
			if(input.getDataType()==DataType.MATRIX)
				ec.releaseMatrixInput(input.getName());
	}
}
