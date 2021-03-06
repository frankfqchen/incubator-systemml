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

package org.apache.sysml.hops.codegen.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.apache.sysml.api.DMLException;
import org.apache.sysml.hops.AggUnaryOp;
import org.apache.sysml.hops.BinaryOp;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.UnaryOp;
import org.apache.sysml.hops.Hop.AggOp;
import org.apache.sysml.hops.Hop.Direction;
import org.apache.sysml.hops.Hop.OpOp2;
import org.apache.sysml.hops.TernaryOp;
import org.apache.sysml.hops.codegen.cplan.CNode;
import org.apache.sysml.hops.codegen.cplan.CNodeBinary;
import org.apache.sysml.hops.codegen.cplan.CNodeBinary.BinType;
import org.apache.sysml.hops.codegen.cplan.CNodeCell;
import org.apache.sysml.hops.codegen.cplan.CNodeData;
import org.apache.sysml.hops.codegen.cplan.CNodeTpl;
import org.apache.sysml.hops.codegen.cplan.CNodeUnary;
import org.apache.sysml.hops.codegen.cplan.CNodeUnary.UnaryType;
import org.apache.sysml.hops.codegen.cplan.CNodeTernary;
import org.apache.sysml.hops.codegen.cplan.CNodeTernary.TernaryType;
import org.apache.sysml.hops.rewrite.HopRewriteUtils;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.runtime.codegen.SpoofCellwise.CellType;
import org.apache.sysml.runtime.matrix.data.Pair;

public class CellTpl extends BaseTpl 
{
	
	public CellTpl() {
		super(TemplateType.CellTpl);
	}
	
	@Override
	public boolean openTpl(Hop hop) {
		return isValidOperation(hop);
	}

	@Override
	public boolean findTplBoundaries(Hop initialHop, CplanRegister cplanRegister) {
		_initialHop = initialHop;
		rFindCellwisePattern(initialHop, new HashMap<Long, Hop>());
		
		//if cplanRegister has the initial hop then no need to reconstruct
		if(cplanRegister.containsHop(TemplateType.CellTpl, _initialHop.getHopID()))
			return false;
			
		//re-assign initialHop to fuse the sum/rowsums (before checking for chains)
		//TODO add aggbinary (vector tsmm) as potential head for cellwise operation
		for (Hop h : _initialHop.getParent()) {
			if( h instanceof AggUnaryOp && ((AggUnaryOp) h).getOp() == AggOp.SUM 
				&& ((AggUnaryOp) h).getDirection()!= Direction.Col ) {
				_initialHop = h;  
			}
		}
		
		//unary matrix && endHop found && endHop is not direct child of the initialHop (i.e., chain of operators)
		if(_endHop != null && _endHop != _initialHop)
		{
			// if final hop is unary add its child to the input 
			if(_endHop instanceof UnaryOp)
				_matrixInputs.add(_endHop.getInput().get(0));
			//if one input is scalar then add the other as major input
			else if(_endHop.getInput().get(0).getDataType() == DataType.SCALAR)
				_matrixInputs.add(_endHop.getInput().get(1));
			else if(_endHop.getInput().get(1).getDataType() == DataType.SCALAR)
				_matrixInputs.add(_endHop.getInput().get(0));
			//if one is matrix and the other is vector add the matrix
			else if(TemplateUtils.isMatrix(_endHop.getInput().get(0)) && TemplateUtils.isVector(_endHop.getInput().get(1)) )
				_matrixInputs.add(_endHop.getInput().get(0));
			else if(TemplateUtils.isMatrix(_endHop.getInput().get(1)) && TemplateUtils.isVector(_endHop.getInput().get(0)) )
				_matrixInputs.add(_endHop.getInput().get(1));
			//both are vectors (add any of them)
			else
				_matrixInputs.add(_endHop.getInput().get(0));
				
			return true;
		}
		
		return false;
	}
	
	private void rFindCellwisePattern(Hop h, HashMap<Long,Hop> memo)
	{
		if(memo.containsKey(h.getHopID()))
			return;
		
		//stop recursion if stopping operator
		if(h.getDataType() == DataType.SCALAR || !isValidOperation(h))
			return;
		
		//process childs recursively
		_endHop = h;
		for( Hop in : h.getInput() )
		{
			//propagate the _endHop from bottom to top
			if(memo.containsKey(in.getHopID()))
				_endHop=memo.get(in.getHopID());
			else
				rFindCellwisePattern(in,memo);
		}
	
		memo.put(h.getHopID(), _endHop);	
	}

	@Override
	public LinkedHashMap<Long, Pair<Hop[],CNodeTpl>> constructTplCplan(boolean compileLiterals)
			throws DMLException {
		//re-assign the dimensions of inputs to match the generated code dimensions
		_initialCnodes.add(new CNodeData(_matrixInputs.get(0), 1, 1, DataType.SCALAR));
		
		rConstructCellCplan(_initialHop,_initialHop, new HashSet<Long>(), compileLiterals);
		return _cpplans;
	}
	
	public CNode fuseCellWise(Hop initialHop,Hop matrixInput, boolean compileLiterals)
			throws DMLException {
		//re-assign the dimensions of inputs to match the generated code dimensions
		_initialHop = initialHop;
		_matrixInputs.add(matrixInput);
		
		constructTplCplan(compileLiterals);
		Entry<Long, Pair<Hop[],CNodeTpl>> toplevel = TemplateUtils.getTopLevelCpplan(_cpplans);
		if(toplevel != null)
			return toplevel.getValue().getValue().getOutput();
		else 
			return null;
	}
	
	private void rConstructCellCplan(Hop root, Hop hop, HashSet<Long> memo, boolean compileLiterals) 
		throws DMLException
	{
		if( memo.contains(hop.getHopID()) )
			return;
		
		
		//process childs recursively
		for( Hop c : hop.getInput() )
			rConstructCellCplan(root, c, memo, compileLiterals);
		
		 // first hop to enter here should be _endHop
		if(TemplateUtils.inputsAreGenerated(hop,_matrixInputs,_cpplans))  
		// if direct children are DataGenOps, literals, or already in the cpplans then we are ready to generate code
		{
			CNodeCell cellTmpl = null;
			
			//Fetch operands
			CNode out = null;
			ArrayList<CNode> addedCNodes = new ArrayList<CNode>();
			ArrayList<Hop> addedHops = new ArrayList<Hop>();
			ArrayList<CNode> cnodeData = TemplateUtils.fetchOperands(hop, _cpplans, addedCNodes, addedHops, _initialCnodes, compileLiterals);
			
			//if operands are scalar or independent from X 
			boolean independentOperands = hop != root && (hop.getDataType() == DataType.SCALAR || TemplateUtils.isOperandsIndependent(cnodeData, addedHops, new String[] {_matrixInputs.get(0).getName()}));
			if(!independentOperands)
			{
				if(hop instanceof UnaryOp)
				{
					CNode cdata1 = cnodeData.get(0);
					
					//Primitive Operation haas the same name as Hop Type OpOp1
					String primitiveOpName = ((UnaryOp)hop).getOp().toString();
					out = new CNodeUnary(cdata1, UnaryType.valueOf(primitiveOpName));
				}
				else if(hop instanceof BinaryOp)
				{
					BinaryOp bop = (BinaryOp) hop;
					CNode cdata1 = cnodeData.get(0);
					CNode cdata2 = cnodeData.get(1);
					
					//Primitive Operation has the same name as Hop Type OpOp2
					String primitiveOpName = bop.getOp().toString();
					
					//cdata1 is vector
					if( TemplateUtils.isColVector(cdata1) )
						cdata1 = new CNodeUnary(cdata1, UnaryType.LOOKUP_R);
					else if( cdata1 instanceof CNodeData && hop.getInput().get(0).getDataType().isMatrix() )
						cdata1 = new CNodeUnary(cdata1, UnaryType.LOOKUP_RC);
					
					//cdata2 is vector
					if( TemplateUtils.isColVector(cdata2) )
						cdata2 = new CNodeUnary(cdata2, UnaryType.LOOKUP_R);
					else if( cdata2 instanceof CNodeData && hop.getInput().get(1).getDataType().isMatrix() )
						cdata2 = new CNodeUnary(cdata2, UnaryType.LOOKUP_RC);
					
					if( bop.getOp()==OpOp2.POW && cdata2.isLiteral() && cdata2.getVarname().equals("2") )
						out = new CNodeUnary(cdata1, UnaryType.POW2);
					else if( bop.getOp()==OpOp2.MULT && cdata2.isLiteral() && cdata2.getVarname().equals("2") )
						out = new CNodeUnary(cdata1, UnaryType.MULT2);
					else //default binary	
						out = new CNodeBinary(cdata1, cdata2, BinType.valueOf(primitiveOpName));
				}
				else if(hop instanceof TernaryOp) 
				{
					TernaryOp top = (TernaryOp) hop;
					CNode cdata1 = cnodeData.get(0);
					CNode cdata2 = cnodeData.get(1);
					CNode cdata3 = cnodeData.get(2);
					
					//cdata1 is vector
					if( TemplateUtils.isColVector(cdata1) )
						cdata1 = new CNodeUnary(cdata1, UnaryType.LOOKUP_R);
					else if( cdata1 instanceof CNodeData && hop.getInput().get(0).getDataType().isMatrix() )
						cdata1 = new CNodeUnary(cdata1, UnaryType.LOOKUP_RC);
					
					//cdata3 is vector
					if( TemplateUtils.isColVector(cdata3) )
						cdata3 = new CNodeUnary(cdata3, UnaryType.LOOKUP_R);
					else if( cdata3 instanceof CNodeData && hop.getInput().get(2).getDataType().isMatrix() )
						cdata3 = new CNodeUnary(cdata3, UnaryType.LOOKUP_RC);
					
					//construct ternary cnode, primitive operation derived from OpOp3
					out = new CNodeTernary(cdata1, cdata2, cdata3, 
							TernaryType.valueOf(top.getOp().toString()));
				}
				else if (hop instanceof AggUnaryOp && ((AggUnaryOp)hop).getOp() == AggOp.SUM
					&& (((AggUnaryOp) hop).getDirection() == Direction.RowCol 
					|| ((AggUnaryOp) hop).getDirection() == Direction.Row) && root == hop)
				{
					out = cnodeData.get(0);
				}
			}
			// wire output to the template
			if(out != null || independentOperands)
			{
				if(_cpplans.isEmpty())
				{
					//first initialization has to have the first variable as input
					ArrayList<CNode> initialInputs = new ArrayList<CNode>();					
					
					if(independentOperands) // pass the hop itself as an input instead of its children
					{
						CNode c =  new CNodeData(hop);
						initialInputs.addAll(_initialCnodes);
						initialInputs.add(c);
						cellTmpl = new CNodeCell(initialInputs, c); 
						cellTmpl.setDataType(hop.getDataType());
						cellTmpl.setCellType(CellType.NO_AGG);
						cellTmpl.setMultipleConsumers(hop.getParent().size()>1);
						
						_cpplans.put(hop.getHopID(), new Pair<Hop[],CNodeTpl>(new Hop[] {_matrixInputs.get(0),hop} ,cellTmpl));
					}
					else
					{
						initialInputs.addAll(_initialCnodes);
						initialInputs.addAll(cnodeData);
						cellTmpl =  new CNodeCell(initialInputs, out); 
						cellTmpl.setDataType(hop.getDataType());
						cellTmpl.setCellType(CellType.NO_AGG);
						cellTmpl.setMultipleConsumers(hop.getParent().size()>1);
						
						//Hop[] hopArray = new Hop[hop.getInput().size()+1];
						Hop[] hopArray = new Hop[addedHops.size()+1];
						hopArray[0] = _matrixInputs.get(0);
						
						//System.arraycopy( hop.getInput().toArray(), 0, hopArray, 1, hop.getInput().size());
						System.arraycopy( addedHops.toArray(), 0, hopArray, 1, addedHops.size());
						
						_cpplans.put(hop.getHopID(), new Pair<Hop[],CNodeTpl>(hopArray,cellTmpl));
					}
				}
				else
				{
					if(independentOperands)
					{
						CNode c =  new CNodeData(hop);
						//clear Operands
						addedCNodes.clear();
						addedHops.clear();
						
						//added the current hop as the input
						addedCNodes.add(c);
						addedHops.add(hop);
						out = c;
					}
					//wire the output to existing or new template	
					TemplateUtils.setOutputToExistingTemplate(hop, out, _cpplans, addedCNodes, addedHops);
				}
			}
			memo.add(hop.getHopID());
		}
	}

	private boolean isValidOperation(Hop hop) 
	{	
		//prepare indicators for binary operations
		boolean isBinaryMatrixScalar = false;
		boolean isBinaryMatrixVector = false;
		boolean isBinaryMatrixMatrixDense = false;
		if( hop instanceof BinaryOp && hop.getDataType().isMatrix() ) {
			Hop left = hop.getInput().get(0);
			Hop right = hop.getInput().get(1);
			DataType ldt = left.getDataType();
			DataType rdt = right.getDataType();
			
			isBinaryMatrixScalar = (ldt.isScalar() || rdt.isScalar());	
			isBinaryMatrixVector = hop.dimsKnown() 
				&& ((ldt.isMatrix() && TemplateUtils.isVectorOrScalar(right) && !TemplateUtils.isBinaryMatrixRowVector(hop)) 
				|| (rdt.isMatrix() && TemplateUtils.isVectorOrScalar(left) && !TemplateUtils.isBinaryMatrixRowVector(hop)) );
			isBinaryMatrixMatrixDense = hop.dimsKnown() && HopRewriteUtils.isEqualSize(left, right)
				&& ldt.isMatrix() && rdt.isMatrix() && !HopRewriteUtils.isSparse(left) && !HopRewriteUtils.isSparse(right);
		}
				
		//prepare indicators for ternary operations
		boolean isTernaryVectorScalarVector = false;
		boolean isTernaryMatrixScalarMatrixDense = false;
		if( hop instanceof TernaryOp && hop.getInput().size()==3 && hop.dimsKnown() 
			&& HopRewriteUtils.checkInputDataTypes(hop, DataType.MATRIX, DataType.SCALAR, DataType.MATRIX)) {
			Hop left = hop.getInput().get(0);
			Hop right = hop.getInput().get(2);
			
			isTernaryVectorScalarVector = TemplateUtils.isVector(left) && TemplateUtils.isVector(right);
			isTernaryMatrixScalarMatrixDense = HopRewriteUtils.isEqualSize(left, right) 
				&& !HopRewriteUtils.isSparse(left) && !HopRewriteUtils.isSparse(right);
		}
		
		//check supported unary, binary, ternary operations
		return hop.getDataType() == DataType.MATRIX && TemplateUtils.isOperationSupported(hop) && (hop instanceof UnaryOp 
				|| isBinaryMatrixScalar || isBinaryMatrixVector || isBinaryMatrixMatrixDense 
				|| isTernaryVectorScalarVector || isTernaryMatrixScalarMatrixDense);	
	}
}
