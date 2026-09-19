import { PageResponse, request, query } from '@/lib/api';
import { ErrorPanel } from '@/components/ErrorPanel';
import { EmptyState } from '@/components/EmptyState';
import { StatusBadge } from '@/components/StatusBadge';
import { formatBytes, formatRelativeTime } from '@/lib/format';
import { accessToken } from '@/lib/session';

export const metadata = { title: 'Assets — HATIS' };

interface AssetSummary {
  id: string;
  projectId: string;
  filename: string;
  contentType: string;
  byteSize: number;
  status: string;
  scanStatus: string;
  classification: string;
  createdAt: string;
}

interface Project {
  id: string;
  name: string;
}

export default async function AssetsPage({
  searchParams,
}: {
  searchParams: Promise<{ project?: string }>;
}) {
  const params = await searchParams;
  const token = await accessToken();

  let projects: Project[] = [];
  let assets: AssetSummary[] = [];
  let error: unknown = null;

  try {
    const projectPage = await request<PageResponse<Project>>('/v1/projects?page=0&size=100', {
      accessToken: token ?? undefined,
    });
    projects = projectPage.items;

    const projectId = params.project ?? projects[0]?.id;
    if (projectId) {
      const assetPage = await request<PageResponse<AssetSummary>>(
        `/v1/assets${query({ projectId, size: 50 })}`,
        { accessToken: token ?? undefined },
      );
      assets = assetPage.items;
    }
  } catch (cause) {
    error = cause;
  }

  const selectedProject = params.project ?? projects[0]?.id ?? '';

  return (
    <>
      <div className="page-head">
        <h1>Assets</h1>
        <p className="muted">
          Nothing is downloadable until it has been scanned. Delivery is by signed URL, never from the
          private bucket.
        </p>
      </div>

      {error ? <ErrorPanel error={error} /> : null}

      <div className="card">
        <form method="GET">
          <label>
            Project
            <select name="project" defaultValue={selectedProject}>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
          <button type="submit">Filter</button>
        </form>
      </div>

      {assets.length === 0 && !error ? <EmptyState message="No assets in this project." /> : null}

      {assets.length > 0 ? (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>File</th>
                <th>Type</th>
                <th>Size</th>
                <th>Scan</th>
                <th>Status</th>
                <th>Uploaded</th>
              </tr>
            </thead>
            <tbody>
              {assets.map((asset) => (
                <tr key={asset.id}>
                  <td className="mono">{asset.filename}</td>
                  <td className="muted">{asset.contentType}</td>
                  <td>{formatBytes(asset.byteSize)}</td>
                  <td>
                    <StatusBadge status={asset.scanStatus} />
                  </td>
                  <td>
                    <StatusBadge status={asset.status} />
                  </td>
                  <td className="muted">{formatRelativeTime(asset.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}
