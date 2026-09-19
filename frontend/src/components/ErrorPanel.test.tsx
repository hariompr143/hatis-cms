import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api';
import { ErrorPanel } from './ErrorPanel';

describe('ErrorPanel', () => {
  it('explains a platform error code and shows the reference id', () => {
    const error = new ApiError(409, 'quota_exceeded', 'Quota exceeded for content_items', 'corr-123', {});
    render(<ErrorPanel error={error} />);

    expect(screen.getByText(/plan limit/)).toBeInTheDocument();
    expect(screen.getByText('Quota exceeded for content_items')).toBeInTheDocument();
    expect(screen.getByText('corr-123')).toBeInTheDocument();
  });

  it('is marked as an alert so assistive technology announces it', () => {
    render(<ErrorPanel error={new ApiError(403, 'forbidden', 'No', null, {})} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('omits the reference line when the platform supplied no correlation id', () => {
    render(<ErrorPanel error={new ApiError(500, 'operation_failed', 'Failed', null, {})} />);
    expect(screen.queryByText(/^Reference:/)).not.toBeInTheDocument();
  });

  it('handles a non-ApiError without crashing', () => {
    render(<ErrorPanel error={new TypeError('network down')} />);
    expect(screen.getByText('Unexpected error')).toBeInTheDocument();
    expect(screen.getByText('network down')).toBeInTheDocument();
  });

  it('handles a thrown string', () => {
    render(<ErrorPanel error="something odd" />);
    expect(screen.getByText('something odd')).toBeInTheDocument();
  });
});
