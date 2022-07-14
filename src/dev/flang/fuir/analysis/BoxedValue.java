/*

This file is part of the Fuzion language implementation.

The Fuzion language implementation is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as published
by the Free Software Foundation, version 3 of the License.

The Fuzion language implementation is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
License for more details.

You should have received a copy of the GNU General Public License along with The
Fuzion language implementation.  If not, see <https://www.gnu.org/licenses/>.

*/

/*-----------------------------------------------------------------------
 *
 * Tokiwa Software GmbH, Germany
 *
 * Source of class BoxedValue
 *
 *---------------------------------------------------------------------*/

package dev.flang.fuir.analysis;


/**
 * BoxedValue represents a Value other than Instance that was boxed, i.e., turned
 * into a ref.
 *
 * @author Fridtjof Siebert (siebert@tokiwa.software)
 */
public class BoxedValue extends Value
{


  /*-----------------------------  classes  -----------------------------*/


  /*----------------------------  constants  ----------------------------*/


  /*----------------------------  variables  ----------------------------*/


  /**
   * The clazz this is an instance of.
   */
  int _clazz;


  /**
   * The original, non-boxed value.
   */
  Value _original;


  /*---------------------------  consructors  ---------------------------*/


  /**
   * Create Boxed Value
   *
   * @param orignal the unboxed value
   */
  public BoxedValue(Value original, int vc, int rc)
  {
    if (PRECONDITIONS) require
      (!(original instanceof Instance));

    _clazz = rc;
    _original = original;
  }


  /*-----------------------------  methods  -----------------------------*/


  /**
   * Create the union of the values 'this' and 'v'. This is called by join()
   * after common cases (same instnace, UNDEFINED) have been handled.
   */
  public Value joinInstances(Value v)
  {
    return new ValueSet(this, v);
  }


  /**
   * Unbox this value.
   */
  Value unbox(int vc)
  {
    return _original;
  }


  /**
   * Create human-readable string from this value.
   */
  public String toString()
  {
    return "boxed:" + _original;
  }

}

/* end of file */
