/*
 * License Applicability. Except to the extent portions of this file are
 * made subject to an alternative license as permitted in the SGI Free
 * Software License B, Version 1.1 (the "License"), the contents of this
 * file are subject only to the provisions of the License. You may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at Silicon Graphics, Inc., attn: Legal Services, 1600
 * Amphitheatre Parkway, Mountain View, CA 94043-1351, or at:
 * 
 * http://oss.sgi.com/projects/FreeB
 * 
 * Note that, as provided in the License, the Software is distributed on an
 * "AS IS" basis, with ALL EXPRESS AND IMPLIED WARRANTIES AND CONDITIONS
 * DISCLAIMED, INCLUDING, WITHOUT LIMITATION, ANY IMPLIED WARRANTIES AND
 * CONDITIONS OF MERCHANTABILITY, SATISFACTORY QUALITY, FITNESS FOR A
 * PARTICULAR PURPOSE, AND NON-INFRINGEMENT.
 * 
 * Original Code. The Original Code is: OpenGL Sample Implementation,
 * Version 1.2.1, released January 26, 2000, developed by Silicon Graphics,
 * Inc. The Original Code is Copyright (c) 1991-2000 Silicon Graphics, Inc.
 * Copyright in any portions created by third parties is as indicated
 * elsewhere herein. All Rights Reserved.
 * 
 * Additional Notice Provisions: The application programming interfaces
 * established by SGI in conjunction with the Original Code are The
 * OpenGL(R) Graphics System: A Specification (Version 1.2.1), released
 * April 1, 1999; The OpenGL(R) Graphics System Utility Library (Version
 * 1.3), released November 4, 1998; and OpenGL(R) Graphics with the X
 * Window System(R) (Version 1.3), released October 19, 1998. This software
 * was created using the OpenGL(R) version 1.2.1 Sample Implementation
 * published by SGI, but has not been independently verified as being
 * compliant with the OpenGL(R) version 1.2.1 Specification.
 */

package com.sun.opengl.impl.mipmap;

import java.nio.*;

/**
 *
 * @author Administrator
 */
public class Extract5551 implements Extract {
  
  /** Creates a new instance of Extract5551 */
  public Extract5551() {
  }
  
  public void extract( boolean isSwap, ByteBuffer packedPixel, float[] extractComponents ) {
    int ushort = 0;
    
    if( isSwap ) {
      ushort = 0x0000FFFF & Mipmap.GLU_SWAP_2_BYTES( packedPixel.getShort() );
    } else {
      ushort = 0x0000FFFF & packedPixel.getShort();
    }
    
    // 11111000,00000000 == 0xF800
    // 00000111,11000000 == 0x07C0
    // 00000000,00111110 == 0x003E
    // 00000000,00000001 == 0x0001
    
    extractComponents[0] = (float)( ( ushort & 0xF800 ) >> 11 ) / 31.0f;
    extractComponents[1] = (float)( ( ushort & 0x00F0 ) >>  6 ) / 31.0f;
    extractComponents[2] = (float)( ( ushort & 0x0F00 ) >>  1 ) / 31.0f;
    extractComponents[3] = (float)( ( ushort & 0xF000 )       );
  }
  
  public void shove( float[] shoveComponents, int index, ByteBuffer packedPixel ) {
    // 11110000,00000000 == 0xF000
    // 00001111,00000000 == 0x0F00
    // 00000000,11110000 == 0x00F0
    // 00000000,00001111 == 0x000F
    
    assert( 0.0f <= shoveComponents[0] && shoveComponents[0] <= 1.0f );
    assert( 0.0f <= shoveComponents[1] && shoveComponents[1] <= 1.0f );
    assert( 0.0f <= shoveComponents[2] && shoveComponents[2] <= 1.0f );
    assert( 0.0f <= shoveComponents[3] && shoveComponents[3] <= 1.0f );
    
    // due to limited precision, need to round before shoving
    int ushort = (((int)((shoveComponents[0] * 31) + 0.5f) << 11) & 0x0000F800 );
    ushort |= (((int)((shoveComponents[1] * 31) + 0.5f) <<  6) & 0x000007C0 );
    ushort |= (((int)((shoveComponents[2] * 31) + 0.5f) <<  1) & 0x0000003E );
    ushort |= (((int)((shoveComponents[3]) + 0.5f)) & 0x00000001 );
    packedPixel.asShortBuffer().put( index, (short)ushort );
  }
}
