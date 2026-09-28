/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path utils/cb.pipe.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'cb',
})
export class CbPipe implements PipeTransform {
  transform<A extends unknown[], R>(callback: (...args: A) => R, ...args: A): R {
    return callback(...args);
  }
}
